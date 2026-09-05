package com.jdandroid.hoster

import com.jdandroid.core.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * ddownload free flow (no account). Page parsing lives in [DdownloadFreePage],
 * network access (client, cookies, redirects) in [DdownloadHoster]; this class
 * holds only the flow: fetch the file page, check blocks, determine the captcha
 * type, wait out the countdown or hand it to the engine as wait time, submit
 * the form and follow the direct link.
 */
internal class DdownloadFree(private val hoster: DdownloadHoster) {

    /**
     * Intermediate state of a free flow per file code: the parsed form ("rand"
     * id, solved span captcha), name and size, and the time from which the
     * form may be submitted. The cookies live in the free client's store
     * (clientFor(0)). Without this state every retry would reload the page,
     * the countdown would show the same value again and the entry would loop
     * without progress (a wait does not count as an attempt).
     */
    private class FreeSession(
        val form: Map<String, String>,
        val fileName: String?,
        val fileSize: Long,
        val readyAt: Long
    ) {
        /** Not submitted long after the ready time: the id is probably invalid, reload. */
        val expired: Boolean get() = System.currentTimeMillis() > readyAt + GRACE_MS

        private companion object {
            /** How long after the ready time a remembered form is still used. */
            const val GRACE_MS = 10L * 60 * 1000
        }
    }

    private val freeSessions = ConcurrentHashMap<String, FreeSession>()

    /**
     * Countdowns up to this run in-process; longer ones become engine wait
     * time so the job neither holds a slot nor keeps the wake lock.
     */
    private val MAX_INLINE_COUNTDOWN = 5

    /** Minimum wait after a block without a concrete time (seconds). */
    private val MIN_RETRY_WAIT = 60

    /** Block without an exact time ("daily limit", "too many"): one hour. */
    private val LIMIT_FALLBACK_WAIT = 60 * 60

    /** Reason for a block; the engine message counts down the remaining time itself. */
    private fun waitText(@Suppress("UNUSED_PARAMETER") seconds: Int): String =
        Texts.t("hoster_ddownload_free_locked")

    /**
     * Unattended flow: fetch the file page, evaluate offline, maintenance,
     * premium limits and blocks (data-wait-seconds or "You have to wait …").
     * Then the captcha type: an XFS span captcha is solved by the app, which
     * submits the free form after the countdown; Turnstile, reCAPTCHA,
     * hCaptcha and image captchas only work in the browser
     * ([CaptchaRequiredException] with the file page). A direct link
     * intercepted by the browser ([FreeHints.direktUrlAusBrowser]) is adopted
     * together with cookies and browser user agent: cf_clearance is bound to
     * the user agent it was issued for.
     */
    suspend fun resolve(url: String, hints: FreeHints): ResolvedLink = withContext(Dispatchers.IO) {
        val code = hoster.fileCode(url)
        val pageUrl = "${hoster.siteBase}/$code"
        hints.direktUrlAusBrowser?.takeIf { it.isNotBlank() }?.let { direct ->
            return@withContext ResolvedLink(
                direct,
                fileNameFromUrl(direct),
                headers = freeHeaders(pageUrl, direct, hints.cookies)
            )
        }

        val client = hoster.clientFor(0L)
        // Form from an earlier attempt (long countdown): do not reload the
        // page, otherwise the countdown would start over
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
            // Remember form and ready time; the next attempt submits it
            freeSessions[code] = FreeSession(
                form, fileName, fileSize, System.currentTimeMillis() + countdown * 1000L
            )
        }

        // Countdown before the download: short waits in-process, long ones
        // back to the engine as wait time (form stays in [freeSessions])
        if (countdown > MAX_INLINE_COUNTDOWN) {
            throw WaitException(countdown + 1, Texts.t("hoster_ddownload_free_countdown"))
        }
        if (countdown > 0) delay((countdown + 1) * 1000L)

        // The form is used up: another attempt reloads the page
        freeSessions.remove(code)
        var resp = with(hoster) { client.fetch(pageUrl, form = form, referer = pageUrl, followRedirects = false) }
        hoster.checkBlocked(resp)
        var direct: String? = null
        var currentUrl = pageUrl
        var hops = 0
        while (direct == null && hops++ < 6) {
            if (resp.code in 300..399 && !resp.location.isNullOrBlank()) {
                val target = hoster.resolveLocation(currentUrl, resp.location!!)
                if (hoster.isFileServerUrl(target)) { direct = target; break }
                resp = with(hoster) { client.fetch(target, referer = currentUrl, followRedirects = false) }
                currentUrl = target
                hoster.checkBlocked(resp)
                // Response is already the file: the fetched address is the link
                if (resp.code in 200..299 && resp.isFile) { direct = target; break }
                continue
            }
            if (resp.code in 200..299 && resp.isFile) {
                // The file itself came back on the POST: its address fetched
                // via GET is only the file page, so there is no usable link
                throw HosterException(Texts.t("hoster_ddownload_no_direct_link", resp.code), permanent = false)
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
            // Unknown response: a retry costs nothing, so never permanent
            throw HosterException(Texts.t("hoster_ddownload_no_direct_link", resp.code), permanent = false)
        }
        ResolvedLink(
            direct,
            fileName ?: fileNameFromUrl(direct),
            fileSize,
            headers = freeHeaders(pageUrl, direct, hoster.cookieHeader(0L, direct))
        )
    }

    /**
     * Permanent and temporary blocks of the free page: maintenance
     * (temporary), premium only (permanent), wait time from the page (+1 s
     * margin) or a limit without a time (one hour).
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
     * Fields of the free form (op=download2, method_free="Free Download",
     * referer = file page) plus captcha fields; the "rand" id comes from the page.
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
     * Headers for the file request in free mode: browser user agent
     * (cf_clearance is bound to it), Referer of the file page and the cookies
     * in case the file server requires them. Cookies only for the hoster's own
     * hosts, never for a foreign CDN [directUrl].
     */
    fun freeHeaders(pageUrl: String, directUrl: String, cookies: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = hoster.browserUa
        headers["Referer"] = pageUrl
        if (DirectLinks.isSiteHost(directUrl, hoster.siteHosts)) {
            cookies?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        }
        return headers
    }

    /** File name from the last path segment of a file server address (only with an extension). */
    fun fileNameFromUrl(url: String): String? =
        url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && Regex("""\.[A-Za-z0-9]{1,10}$""").containsMatchIn(it) }
}
