package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.data.plainPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * rapidgator.net – offizielle API v2 fuer Premium-Konten; ohne Konto der
 * Free-Ablauf der Website ([resolveFree]: Timer, Freischaltung, Captcha).
 */
class RapidgatorHoster : Hoster {

    override val id = "rapidgator"
    override val displayName = "Rapidgator"
    override val accountType = AccountType.USERNAME_PASSWORD
    override val accountHint: String
        get() = Texts.t("hoster_rapidgator_account_hint")

    private val base = "https://rapidgator.net/api/v2"
    private val pattern = Regex("""https?://(?:www\.)?(?:rapidgator\.net|rg\.to)/file/\S+""")

    /** Session-Token pro Account-Id zwischenspeichern. */
    private val tokens = java.util.concurrent.ConcurrentHashMap<Long, String>()

    override val siteHosts = setOf("rapidgator.net", "www.rapidgator.net", "rg.to", "www.rg.to")

    override val supportsFree = true

    override fun matches(url: String) = pattern.containsMatchIn(url)

    // ------------------------------------------------------------------
    // Free-Modus (Website-Ablauf ohne Konto)
    // ------------------------------------------------------------------

    private val siteBase = "https://rapidgator.net"

    /** Captcha-Seite: dort loest der Nutzer das Turnstile im eingebetteten Browser. */
    private val captchaPageUrl get() = "$siteBase/download/captcha"

    /**
     * Browser-Kennung wie in der Captcha-Ansicht: Timer, Freischaltung,
     * Captcha und Dateiabruf laufen unter derselben Kennung und denselben Cookies.
     */
    private val browserUa: String
        get() = Http.browserUserAgent
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"

    /**
     * Zustand eines Free-Ablaufs je Datei-Kennung: eigener Cookie-Speicher
     * (PHPSESSID, __token, sdata__), Timer-Kennung und Zeitpunkt, ab dem der
     * Link freigeschaltet werden darf. Lebt nur im Prozess - nach einem Neustart
     * beginnt der Ablauf von vorn.
     */
    private class FreeSession(val pageUrl: String) {
        lateinit var vars: RapidgatorFreeVars
        var fileName: String? = null
        var fileSize = -1L
        var hash: String? = null
        val store = mutableListOf<Cookie>()
        val client: OkHttpClient = Http.client.newBuilder()
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    synchronized(store) {
                        for (c in cookies) {
                            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                            store.add(c)
                        }
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
                    val now = System.currentTimeMillis()
                    store.filter { it.matches(url) && it.expiresAt > now }
                }
            })
            .build()
        var sid: String? = null
        var readyAt = 0L
        var linkReady = false
        val createdAt = System.currentTimeMillis()

        val expired: Boolean get() = System.currentTimeMillis() - createdAt > SESSION_MAX_AGE_MS

        /** Cookie-Header fuer [url], null ohne passende Cookies. */
        fun cookieHeader(url: String): String? {
            val http = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
            val now = System.currentTimeMillis()
            return synchronized(store) {
                store.filter { it.matches(http) && it.expiresAt > now }
                    .joinToString("; ") { "${it.name}=${it.value}" }
            }.ifBlank { null }
        }

        /** Alle Cookies im Set-Cookie-Format fuer den Browser. */
        fun cookiesForBrowser(): List<String> = synchronized(store) {
            val now = System.currentTimeMillis()
            store.filter { it.expiresAt > now }.map { it.toString() }
        }
    }

    private val freeSessions = ConcurrentHashMap<String, FreeSession>()

    /** Neustarts je Datei, wenn der Server Timer oder Zustand nicht anerkennt. */
    private val restarts = ConcurrentHashMap<String, Int>()

    private data class Resp(val code: Int, val body: String, val location: String?, val finalUrl: String)

    private fun FreeSession.fetch(
        url: String,
        referer: String? = null,
        ajax: Boolean = false,
        followRedirects: Boolean = true
    ): Resp {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", browserUa)
            .header("Accept-Language", "en,de;q=0.8")
        if (ajax) {
            builder.header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
        } else {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        referer?.let { builder.header("Referer", it) }
        val c = if (followRedirects) client else {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
        return c.newCall(builder.build()).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty().lowercase()
            // Nur Seiten und JSON als Text lesen, nie eine Datei (Heap)
            val textual = type.isBlank() || type.startsWith("text/") || type.contains("json") ||
                type.contains("javascript") || type.contains("xml")
            val body = if (textual) runCatching { resp.peekBody(Http.MAX_TEXT_BYTES).string() }.getOrDefault("") else ""
            Resp(resp.code, body, resp.header("Location"), resp.request.url.toString())
        }
    }

    private fun waitFor(block: RapidgatorBlock): Nothing = when (block) {
        is RapidgatorBlock.Wait -> throw WaitException(block.seconds, block.text)
        is RapidgatorBlock.Permanent -> throw HosterException(block.text, permanent = true)
        RapidgatorBlock.Restart -> throw HosterException(Texts.t("hoster_rapidgator_restart_flow"), permanent = false)
    }

    /**
     * Free-Modus ohne Konto. Ablauf ohne Nutzer: Dateiseite holen (Offline,
     * Premium-Grenzen, Sperren mit Wartezeit), Timer per AjaxStartTimer
     * starten und die Wartezeit (`secs`, meist 180 s) als [WaitException] an
     * die Engine geben; beim naechsten Versuch den Link per
     * AjaxGetDownloadLink freischalten und die Captcha-Seite pruefen. Steht
     * dort das Turnstile-Formular, gehen die Session-Cookies mit der
     * [CaptchaRequiredException] an die Captcha-Ansicht und der Nutzer loest
     * das Captcha auf der Captcha-Seite; die Navigation auf den Fileserver
     * (`pr<N>.rapidgator.net//?r=download/index&session_id=…`) faengt die
     * Captcha-Ansicht ab und liefert sie als [FreeHints.direktUrlAusBrowser].
     *
     * Alle Schritte laufen ueber dieselbe IP, dieselben Cookies und dieselbe
     * Browser-Kennung: die Freischaltung (`sdata__`) ist an die IP gebunden,
     * ein zu frueher Abruf macht den Timer ungueltig (dann von vorn).
     */
    override suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink =
        withContext(Dispatchers.IO) {
            val (id, nameSegment) = RapidgatorFreePage.fileIdAndName(url)
                ?: throw HosterException(Texts.t("hoster_rapidgator_invalid_link"), true)
            val pageUrl = "$siteBase/file/$id" + (nameSegment?.let { "/$it" } ?: "")

            hints.direktUrlAusBrowser?.takeIf { it.isNotBlank() }?.let { direct ->
                val session = freeSessions.remove(id)
                restarts.remove(id)
                return@withContext ResolvedLink(
                    direct,
                    session?.fileName,
                    session?.fileSize ?: -1,
                    session?.hash,
                    headers = freeHeaders(direct, hints.cookies ?: session?.cookieHeader(direct))
                )
            }

            while (true) {
                val session = freeSessions[id]?.takeUnless { it.expired }
                    ?: startFreeSession(id, pageUrl)

                // Timer laeuft noch: lange Reste an die Engine, kurze im Prozess abwarten
                val remaining = session.readyAt - System.currentTimeMillis()
                if (remaining > MAX_INLINE_WAIT_MS) {
                    throw WaitException(((remaining + 999) / 1000).toInt() + 1, Texts.t("hoster_rapidgator_free_wait"))
                }
                if (remaining > 0) delay(remaining + 500)

                if (!session.linkReady) {
                    val reply = ajax(session, session.vars.getDownloadUrl, "sid" to (session.sid ?: ""))
                    when (reply.state) {
                        "done" -> session.linkReady = true
                        else -> {
                            val block = reply.code?.let { RapidgatorFreePage.classify(it) }
                            if (block != null && block !is RapidgatorBlock.Restart) waitFor(block)
                            // Timer nicht anerkannt (zu frueh, Zustand weg): von vorn
                            restart(id, Texts.t("hoster_rapidgator_unlock_rejected", reply.code ?: reply.state))
                            continue
                        }
                    }
                }

                // Captcha-Seite: bei verlorenem Zustand (andere IP, abgelaufen)
                // leitet sie auf die Dateiseite zurueck - dann von vorn
                val page = session.fetch(resolveUrl(session.vars.captchaUrl), referer = pageUrl, followRedirects = false)
                when {
                    page.code == 500 -> throw WaitException(30 * 60, Texts.t("hoster_rapidgator_download_not_possible"))
                    page.code in 300..399 -> {
                        restart(id, Texts.t("hoster_rapidgator_captcha_page_redirect"))
                        continue
                    }
                    page.code !in 200..299 -> throw HosterException(
                        Texts.t("hoster_rapidgator_captcha_page_unreachable", page.code), permanent = false
                    )
                }
                RapidgatorFreePage.directLink(page.body)?.let { direct ->
                    // Kein Captcha verlangt: Direktlink liegt schon vor
                    freeSessions.remove(id)
                    restarts.remove(id)
                    return@withContext ResolvedLink(
                        direct, session.fileName, session.fileSize, session.hash,
                        headers = freeHeaders(direct, session.cookieHeader(direct))
                    )
                }
                if (RapidgatorFreePage.hasCaptchaForm(page.body)) {
                    // Session-Cookies gehen mit: die Captcha-Ansicht setzt sie beim Oeffnen
                    throw CaptchaRequiredException(
                        captchaPageUrl, Texts.t("hoster_rapidgator_captcha_browser"),
                        cookieUrl = siteBase, cookies = session.cookiesForBrowser()
                    )
                }
                RapidgatorFreePage.pageBlock(page.body)?.let { waitFor(it) }
                throw HosterException(Texts.t("hoster_rapidgator_no_direct_link", page.code), permanent = false)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }

    /**
     * Dateiseite holen, auswerten und den Timer starten. Kehrt nie normal
     * zurueck: die Wartezeit des Timers geht als [WaitException] an die Engine,
     * die Session bleibt fuer den naechsten Versuch gespeichert.
     */
    private fun startFreeSession(id: String, pageUrl: String): Nothing {
        freeSessions.remove(id)
        val session = FreeSession(pageUrl)
        // Englische Texte erzwingen, damit die Fehlermuster greifen
        session.store.add(Cookie.Builder().name("lang").value("en").domain("rapidgator.net").path("/").build())
        val page = session.fetch(pageUrl, referer = "$siteBase/")
        // Unbekannte Kennung: 302 auf /article/premium; rg.to leitet auf rapidgator.net weiter
        if (page.code == 404 || !page.finalUrl.contains("/file/$id", ignoreCase = true) ||
            RapidgatorFreePage.isOffline(page.body)
        ) {
            throw FileOfflineException()
        }
        if (page.code !in 200..299) {
            throw HosterException(Texts.t("hoster_rapidgator_file_page_unreachable", page.code), permanent = false)
        }
        RapidgatorFreePage.pageBlock(page.body)?.let { waitFor(it) }
        val vars = RapidgatorFreePage.freeVars(page.body)
            ?: throw HosterException(Texts.t("hoster_rapidgator_no_free_offer"), permanent = false)
        session.vars = vars
        session.fileName = RapidgatorFreePage.fileName(page.body)
        session.fileSize = RapidgatorFreePage.fileSize(page.body)
        session.hash = RapidgatorFreePage.md5(page.body)

        val reply = ajax(session, vars.startTimerUrl, "fid" to vars.fid)
        if (reply.state != "started" || reply.sid.isNullOrBlank()) {
            reply.code?.let { RapidgatorFreePage.classify(it) }?.let { waitFor(it) }
            throw HosterException(
                Texts.t("hoster_rapidgator_free_unavailable", reply.code ?: reply.state), permanent = false
            )
        }
        session.sid = reply.sid
        session.readyAt = System.currentTimeMillis() + vars.secs * 1000L
        freeSessions[id] = session
        throw WaitException(vars.secs + 1, Texts.t("hoster_rapidgator_free_wait"))
    }

    private fun ajax(session: FreeSession, path: String, param: Pair<String, String>): RapidgatorFreePage.AjaxReply {
        val url = resolveUrl(path).toHttpUrl().newBuilder()
            .setQueryParameter(param.first, param.second).build().toString()
        val resp = session.fetch(url, referer = session.pageUrl, ajax = true)
        if (resp.code !in 200..299) {
            throw HosterException(Texts.t("hoster_rapidgator_http_response", resp.code), permanent = false)
        }
        return RapidgatorFreePage.ajaxReply(resp.body)
            ?: throw HosterException(Texts.t("hoster_rapidgator_unexpected_response"), permanent = false)
    }

    /** Zustand verwerfen und von vorn beginnen; nach zu vielen Anlaeufen vorerst aufgeben. */
    private fun restart(id: String, reason: String) {
        freeSessions.remove(id)
        val count = restarts.merge(id, 1) { a, b -> a + b } ?: 1
        if (count > MAX_RESTARTS) {
            restarts.remove(id)
            throw HosterException(Texts.t("hoster_rapidgator_flow_not_accepted", reason), permanent = false)
        }
    }

    private fun resolveUrl(path: String): String =
        if (path.startsWith("http", ignoreCase = true)) path else siteBase + (if (path.startsWith("/")) path else "/$path")

    /**
     * Header fuer den Dateiabruf: Browser-Kennung und Referer der Captcha-Seite
     * wie im Browser, dazu die Cookies (sdata__ gilt fuer alle Subdomains).
     * Cookies only for the hoster's own hosts, never for a foreign [directUrl].
     */
    internal fun freeHeaders(directUrl: String, cookies: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = browserUa
        headers["Referer"] = captchaPageUrl
        if (DirectLinks.isSiteHost(directUrl, siteHosts)) {
            cookies?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        }
        return headers
    }

    /**
     * Fileserver-Adresse nach dem Captcha: `https://pr5.rapidgator.net//?r=download/index&session_id=…`
     * (keine Dateiendung, daher neben [DirectLinks]). Seiten der Hauptdomain zaehlen nie.
     */
    override fun isDirectDownloadUrl(url: String): Boolean {
        if (DirectLinks.isDirectDownloadUrl(url, siteHosts)) return true
        val m = Regex("""^https?://([^/?#]+)/[^?#]*\?(?:[^#]*&)?r=download/index(?:&|$)""", RegexOption.IGNORE_CASE)
            .find(url) ?: return false
        if (m.groupValues[1].lowercase() in siteHosts) return false
        return Regex("""[?&]session_id=[A-Za-z0-9]+""").containsMatchIn(url)
    }

    private companion object {
        /** Reste bis hierhin laufen im Prozess ab, laengere gehen als Wartezeit an die Engine. */
        const val MAX_INLINE_WAIT_MS = 5_000L
        const val SESSION_MAX_AGE_MS = 2L * 60 * 60 * 1000
        const val MAX_RESTARTS = 3
    }

    /** file_id (Hash) aus der Rapidgator-URL: .../file/<id>[/name.html] */
    private fun fileId(url: String): String =
        Regex("""/file/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: throw HosterException(Texts.t("hoster_rapidgator_invalid_link"), true)

    /**
     * API-Aufruf per POST mit Formular-Body: Zugangsdaten und Token gehoeren
     * nicht in die URL (Proxy-/Server-Logs). Antwort begrenzt lesen (Http.MAX_TEXT_BYTES).
     */
    private fun post(url: String, form: Map<String, String>): String {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .post(body)
            .build()
        return Http.client.newCall(request).execute().use { resp ->
            resp.peekBody(Http.MAX_TEXT_BYTES).string()
        }
    }

    /** Vollstaendige JSON-Antwort inklusive "status", ohne Auswertung. */
    private fun callRaw(path: String, params: Map<String, String>): JSONObject {
        val text = post("$base/$path", params)
        return runCatching { JSONObject(text) }
            .getOrElse { throw HosterException(Texts.t("hoster_rapidgator_unexpected_response")) }
    }

    /**
     * Abgelaufene Session (401 ausserhalb des Logins): der einzige Fehler, bei
     * dem ein erneuter Login sinnvoll ist. Alle anderen voruebergehenden Fehler
     * (Sperren, 5xx) werden ohne Neuanmeldung weitergereicht, damit Loginzaehler
     * und Parallel-Session-Limit nicht unnoetig belastet werden.
     */
    internal class TokenExpired(message: String) : HosterException(message, permanent = false)

    /**
     * Fehler aus einer API-Antwort mit status != 200 werfen.
     * [loginCall]: 401 beim Login heisst falsches Passwort/2FA - permanent,
     * damit weder Engine noch Kontopruefung in eine Login-Schleife laufen.
     * Bei anderen Aufrufen ist 401 ein abgelaufener Token ([TokenExpired]),
     * nach erneutem Login behebbar, daher nicht permanent.
     */
    private fun fail(json: JSONObject, loginCall: Boolean): Nothing =
        throw failure(json.optInt("status"), json.optString("details"), loginCall)

    /** Einordnung eines API-Fehlers; getrennt von JSON, damit sie auf der JVM testbar ist. */
    internal fun failure(status: Int, details: String, loginCall: Boolean): HosterException {
        val text = details.ifBlank { Texts.t("hoster_http_status", status) }
        if (status == 401 && !loginCall) return TokenExpired(Texts.t("hoster_rapidgator_api_error", text))
        // Rapidgator meldet unter 403 auch Tageslimit, IP-Sperre und
        // Parallel-Limit - alles voruebergehend. Dauerhaft sind nur falsche
        // Zugangsdaten (401 beim Login), fehlende Datei (404) und fehlendes
        // Premium (402), sofern der Text nichts anderes sagt.
        val transient = text.contains("traffic", true) ||
            text.contains("limit", true) ||
            text.contains("Denied by IP", true) ||
            text.contains("Session", true) ||
            status in 500..599
        val permanent = !transient && (status in listOf(402, 404) || (loginCall && status == 401))
        return HosterException(Texts.t("hoster_rapidgator_api_error", text), permanent = permanent)
    }

    /** Logins pro Konto serialisieren: parallele Downloads sollen eine Session teilen. */
    private val loginLocks = java.util.concurrent.ConcurrentHashMap<Long, Any>()

    private fun call(path: String, params: Map<String, String>, loginCall: Boolean = false): JSONObject {
        val json = callRaw(path, params)
        if (json.optInt("status") != 200) fail(json, loginCall)
        return json.optJSONObject("response") ?: JSONObject()
    }

    /** Login; liefert Token und das "user"-Objekt der Login-Antwort. */
    private fun login(account: Account): Pair<String, JSONObject> {
        val user = account.username ?: throw HosterException(Texts.t("hoster_no_username"), true)
        val pass = account.plainPassword ?: throw HosterException(Texts.t("hoster_no_password"), true)
        val resp = call("user/login", mapOf("login" to user, "password" to pass), loginCall = true)
        val token = resp.optString("token")
        if (token.isBlank()) throw HosterException(Texts.t("hoster_rapidgator_login_failed"), true)
        tokens[account.id] = token
        return token to (resp.optJSONObject("user") ?: JSONObject())
    }

    private fun tokenFor(account: Account): String =
        tokens[account.id] ?: synchronized(loginLocks.getOrPut(account.id) { Any() }) {
            tokens[account.id] ?: login(account).first
        }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        // Mit vorhandenem Token reicht user/info; nur bei 401 (Token abgelaufen)
        // neu anmelden. Ohne Token liefert die Login-Antwort das user-Objekt
        // bereits mit - ein zweiter Aufruf ist unnoetig.
        val cached = tokens[account.id]
        val user: JSONObject = if (cached != null) {
            val json = callRaw("user/info", mapOf("token" to cached))
            when (json.optInt("status")) {
                200 -> json.optJSONObject("response")?.optJSONObject("user") ?: JSONObject()
                401 -> {
                    tokens.remove(account.id)
                    login(account).second
                }
                else -> fail(json, loginCall = false)
            }
        } else {
            login(account).second
        }
        val premiumEnd = user.optLong("premium_end_time", 0) * 1000
        val premium = user.optBoolean("is_premium", false) ||
            premiumEnd > System.currentTimeMillis() ||
            user.optString("state_label").contains("Premium", ignoreCase = true)
        val traffic = user.optJSONObject("traffic")
        // "left"/"total" in Byte; null bei Konten ohne Tageslimit
        val trafficLeft = traffic?.takeUnless { it.isNull("left") }?.optLong("left", -1) ?: -1
        val trafficTotal = traffic?.takeUnless { it.isNull("total") }?.optLong("total", -1) ?: -1
        AccountInfo(
            valid = true,
            premiumUntil = premiumEnd,
            trafficLeft = trafficLeft,
            trafficTotal = trafficTotal,
            trafficUnlimited = premium && trafficLeft < 0 && trafficTotal < 0,
            statusText = if (premium) "Premium" else freeStatusText
        )
    }

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            // Die API verlangt auch fuer file/info eine Session
            if (account == null) {
                return@withContext LinkInfo(online = null, note = Texts.t("hoster_rapidgator_check_needs_account"))
            }
            val id = fileId(url)
            // Login getrennt behandeln: ein Kontoproblem ist kein "Datei offline"
            val token = try {
                tokenFor(account)
            } catch (e: Exception) {
                return@withContext LinkInfo(online = null, note = e.message ?: Texts.t("hoster_rapidgator_login_failed"))
            }
            val query: (String) -> LinkInfo = { t ->
                val file = call("file/info", mapOf("file_id" to id, "token" to t)).optJSONObject("file")
                if (file == null) LinkInfo(online = false, note = Texts.t("hoster_file_not_found"))
                else LinkInfo(
                    online = true,
                    fileName = file.optString("name").ifBlank { null },
                    fileSize = file.optLong("size", -1)
                )
            }
            try {
                query(token)
            } catch (e: TokenExpired) {
                // Nur bei abgelaufenem Token einmal neu anmelden und die Pruefung
                // wiederholen; Sperren und Serverfehler kosten keinen Login.
                tokens.remove(account.id)
                val fresh = runCatching { tokenFor(account) }
                    .getOrElse { return@withContext LinkInfo(online = null, note = it.message) }
                runCatching { query(fresh) }.getOrElse {
                    LinkInfo(online = if (it is HosterException && it.permanent) false else null, note = it.message)
                }
            } catch (e: HosterException) {
                LinkInfo(online = if (e.permanent) false else null, note = e.message)
            }
        }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            if (account == null) {
                throw HosterException(Texts.t("hoster_rapidgator_premium_required"), permanent = true)
            }
            val id = fileId(url)
            val attempt: (String) -> ResolvedLink = { token ->
                val resp = call("file/download", mapOf("file_id" to id, "token" to token))
                val direct = resp.optString("download_url")
                if (direct.isBlank()) throw HosterException(Texts.t("hoster_rapidgator_no_download_url"), true)
                // Name, Groesse und MD5 fuer die Integritaetspruefung; optional,
                // ein Fehler hier darf den Download nicht verhindern.
                val info = runCatching {
                    call("file/info", mapOf("file_id" to id, "token" to token)).optJSONObject("file")
                }.getOrNull()
                ResolvedLink(
                    directUrl = direct,
                    fileName = info?.optString("name")?.ifBlank { null },
                    fileSize = info?.optLong("size", -1) ?: -1,
                    hash = info?.optString("hash")?.lowercase()?.takeIf { it.length == 32 }
                )
            }
            // Nur wenn file/download mit einem vorhandenen Token an einer
            // abgelaufenen Session scheitert, einmal neu anmelden. Sperren (403),
            // Serverfehler und permanente Fehler gehen direkt weiter - kein
            // Login-Loop, kein Login pro Sperr-/Serverfehler.
            val cached = tokens[account.id]
            if (cached != null) {
                try {
                    return@withContext attempt(cached)
                } catch (e: TokenExpired) {
                    tokens.remove(account.id)
                }
            }
            attempt(tokenFor(account))
        }
}
