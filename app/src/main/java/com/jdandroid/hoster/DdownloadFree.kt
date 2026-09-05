package com.jdandroid.hoster

import com.jdandroid.core.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Free-Ablauf von ddownload (ohne Konto). Die Seitenauswertung liegt in
 * [DdownloadFreePage], der Netzzugriff (Client, Cookies, Weiterleitungen)
 * im [DdownloadHoster]; hier steht nur der Ablauf: Dateiseite holen,
 * Sperren pruefen, Captcha-Art bestimmen, Countdown abwarten oder als
 * Wartezeit an die Engine geben, Formular abschicken und dem Direktlink
 * folgen.
 */
internal class DdownloadFree(private val hoster: DdownloadHoster) {

    /**
     * Zwischenstand eines Free-Ablaufs je Dateicode bei langem Countdown: das
     * gelesene Formular (Kennung "rand", geloestes Span-Captcha), Name und
     * Groesse sowie der Zeitpunkt, ab dem das Formular abgeschickt werden
     * darf. Die Cookies liegen im Speicher des Free-Clients (clientFor(0)).
     * Ohne diesen Zwischenstand luede jeder Folgeversuch die Seite neu, der
     * Countdown stuende wieder auf demselben Wert und der Eintrag kreiste
     * ohne Fortschritt (die Versuche zaehlt eine Wartezeit nicht).
     */
    private class FreeSession(
        val form: Map<String, String>,
        val fileName: String?,
        val fileSize: Long,
        val readyAt: Long
    ) {
        /** Lange nach dem Startzeitpunkt nicht abgeschickt: Kennung vermutlich ungueltig, neu laden. */
        val expired: Boolean get() = System.currentTimeMillis() > readyAt + GRACE_MS

        private companion object {
            /** So lange nach dem Startzeitpunkt gilt ein gemerktes Formular noch. */
            const val GRACE_MS = 10L * 60 * 1000
        }
    }

    private val freeSessions = ConcurrentHashMap<String, FreeSession>()

    /** Countdowns bis hierhin laufen im Prozess ab; laengere werden zur Wartezeit der Engine. */
    private val MAX_INLINE_COUNTDOWN = 180

    /** Mindestwartezeit nach einer Sperre ohne konkrete Zeitangabe (Sekunden). */
    private val MIN_RETRY_WAIT = 60

    /** Sperre ohne genaue Zeit ("daily limit", "too many"): eine Stunde. */
    private val LIMIT_FALLBACK_WAIT = 60 * 60

    /** Grund einer Sperre; die Restzeit zaehlt die Engine-Meldung selbst herunter. */
    private fun waitText(@Suppress("UNUSED_PARAMETER") seconds: Int): String =
        Texts.t("hoster_ddownload_free_locked")

    /**
     * Ablauf ohne Nutzer: Dateiseite holen, Offline, Wartung, Premium-Grenzen
     * und Sperren (data-wait-seconds bzw. "You have to wait …") auswerten.
     * Dann die Captcha-Art: ein XFS-Span-Captcha loest die App selbst und
     * schickt nach dem Countdown das Free-Formular ab; Turnstile, reCAPTCHA,
     * hCaptcha und Bild-Captchas gehen nur im Browser
     * ([CaptchaRequiredException] mit der Dateiseite). Kommt aus dem Browser
     * ein abgefangener Direktlink ([FreeHints.direktUrlAusBrowser]), wird er
     * samt Cookies und Browser-Kennung uebernommen: cf_clearance ist an die
     * Kennung gebunden, mit der es ausgestellt wurde.
     */
    suspend fun resolve(url: String, hints: FreeHints): ResolvedLink = withContext(Dispatchers.IO) {
        val code = hoster.fileCode(url)
        val pageUrl = "${hoster.siteBase}/$code"
        hints.direktUrlAusBrowser?.takeIf { it.isNotBlank() }?.let { direct ->
            return@withContext ResolvedLink(
                direct,
                fileNameFromUrl(direct),
                headers = freeHeaders(pageUrl, hints.cookies)
            )
        }

        val client = hoster.clientFor(0L)
        // Formular aus einem frueheren Versuch (langer Countdown): Seite
        // nicht neu laden, sonst begaenne der Countdown von vorn
        val session = freeSessions[code]?.takeUnless { it.expired }
        val form: Map<String, String>
        val fileName: String?
        val fileSize: Long
        val countdown: Int
        if (session != null) {
            form = session.form
            fileName = session.fileName
            fileSize = session.fileSize
            countdown = ((session.readyAt - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
        } else {
            val page = with(hoster) { client.fetch(pageUrl, referer = siteBase) }
            hoster.checkBlocked(page)
            if (page.code == 404 || DdownloadFreePage.isOffline(page.body)) {
                throw FileOfflineException()
            }
            if (page.code !in 200..299) {
                throw HosterException(Texts.t("hoster_ddownload_file_page_unreachable", page.code), permanent = false)
            }
            checkFreeBlockers(page.body)
            fileName = hoster.pageFileName(page.body)
            fileSize = hoster.pageFileSize(page.body)

            val captchaFields = when (val captcha = DdownloadFreePage.captcha(page.body)) {
                is FreeCaptcha.Span -> mapOf("code" to captcha.code)
                FreeCaptcha.None -> emptyMap()
                is FreeCaptcha.Browser -> throw CaptchaRequiredException(
                    pageUrl, Texts.t("hoster_ddownload_captcha_browser", captcha.kind)
                )
                is FreeCaptcha.Image -> throw CaptchaRequiredException(
                    pageUrl, Texts.t("hoster_ddownload_image_captcha")
                )
            }
            form = freeDownloadForm(page.body, code, pageUrl, captchaFields)
            countdown = DdownloadFreePage.countdownSeconds(page.body)
            if (countdown > MAX_INLINE_COUNTDOWN) {
                // Formular samt Startzeitpunkt merken; der naechste Versuch schickt es ab
                freeSessions[code] = FreeSession(
                    form, fileName, fileSize, System.currentTimeMillis() + countdown * 1000L
                )
            }
        }

        // Countdown vor dem Download: kurz im Prozess abwarten, lange
        // Zeiten als Wartezeit an die Engine zurueckgeben (Formular bleibt
        // in [freeSessions])
        if (countdown > MAX_INLINE_COUNTDOWN) {
            throw WaitException(countdown + 1, Texts.t("hoster_ddownload_free_countdown"))
        }
        if (countdown > 0) delay((countdown + 1) * 1000L)

        // Formular ist verbraucht: ein weiterer Versuch laedt die Seite neu
        freeSessions.remove(code)
        var resp = with(hoster) { client.fetch(pageUrl, form = form, referer = pageUrl, followRedirects = false) }
        hoster.checkBlocked(resp)
        var direct: String? = null
        var currentUrl = pageUrl
        var hops = 0
        while (direct == null && hops++ < 6) {
            if (resp.code in 200..299 && resp.isFile) { direct = resp.finalUrl; break }
            if (resp.code in 300..399 && !resp.location.isNullOrBlank()) {
                val target = hoster.resolveLocation(currentUrl, resp.location!!)
                if (hoster.isFileServerUrl(target)) { direct = target; break }
                resp = with(hoster) { client.fetch(target, referer = currentUrl, followRedirects = false) }
                currentUrl = target
                hoster.checkBlocked(resp)
                continue
            }
            direct = hoster.extractDirectLink(resp.body)
            break
        }
        if (direct.isNullOrBlank()) {
            val body = resp.body
            when {
                DdownloadFreePage.isWrongCaptcha(body) -> throw CaptchaRequiredException(
                    pageUrl, Texts.t("hoster_ddownload_captcha_rejected")
                )
                DdownloadFreePage.isExpiredSession(body) -> throw HosterException(
                    Texts.t("hoster_ddownload_download_session_expired"), permanent = false
                )
                DdownloadFreePage.isSkippedCountdown(body) -> throw WaitException(
                    countdown.coerceAtLeast(MIN_RETRY_WAIT) + 1, Texts.t("hoster_ddownload_countdown_skipped")
                )
            }
            checkFreeBlockers(body)
            // Unbekannte Antwort: ein neuer Versuch kostet nichts, daher nie endgueltig
            throw HosterException(Texts.t("hoster_ddownload_no_direct_link", resp.code), permanent = false)
        }
        ResolvedLink(
            direct,
            fileName ?: fileNameFromUrl(direct),
            fileSize,
            headers = freeHeaders(pageUrl, hoster.cookieHeader(0L, direct))
        )
    }

    /**
     * Dauerhafte und zeitliche Sperren der Free-Seite: Wartung (vorübergehend),
     * nur Premium (dauerhaft), Wartezeit aus der Seite (+1 s Reserve) oder
     * Limit ohne Zeitangabe (eine Stunde).
     */
    private fun checkFreeBlockers(html: String) {
        if (DdownloadFreePage.isMaintenance(html)) {
            throw HosterException(Texts.t("hoster_ddownload_maintenance"), permanent = false)
        }
        DdownloadFreePage.premiumOnlyReason(html)?.let { throw HosterException(it, true) }
        val wait = DdownloadFreePage.waitSeconds(html)
        if (wait > 0) throw WaitException(wait + 1, waitText(wait + 1))
        if (DdownloadFreePage.isLimitWithoutTime(html)) {
            throw WaitException(LIMIT_FALLBACK_WAIT, Texts.t("hoster_ddownload_limit_reached_hour"))
        }
    }

    /**
     * Felder des Free-Formulars (op=download2, method_free="Free Download",
     * referer = Dateiseite) plus Captcha-Felder; die Kennung "rand" kommt
     * von der Seite.
     */
    fun freeDownloadForm(
        html: String,
        code: String,
        pageUrl: String,
        captchaFields: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val block = hoster.formBlock(html) ?: html
        val inputs = hoster.hiddenInputs(block).toMutableMap()
        inputs["op"] = "download2"
        inputs["id"] = inputs["id"]?.ifBlank { code } ?: code
        inputs.putIfAbsent("rand", "")
        inputs["referer"] = pageUrl
        inputs["method_free"] = "Free Download"
        inputs["method_premium"] = ""
        inputs.remove("adblock_detected")?.let { inputs["adblock_detected"] = "0" }
        inputs.putAll(captchaFields)
        return inputs
    }

    /**
     * Header fuer den Dateiabruf im Free-Modus: Browser-Kennung (cf_clearance
     * ist daran gebunden), Referer der Dateiseite und die Cookies, falls der
     * Fileserver sie verlangt.
     */
    fun freeHeaders(pageUrl: String, cookies: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = hoster.browserUa
        headers["Referer"] = pageUrl
        cookies?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        return headers
    }

    /** Dateiname aus dem letzten Pfadteil einer Fileserver-Adresse (nur mit Endung). */
    fun fileNameFromUrl(url: String): String? =
        url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && Regex("""\.[A-Za-z0-9]{1,10}$""").containsMatchIn(it) }
}
