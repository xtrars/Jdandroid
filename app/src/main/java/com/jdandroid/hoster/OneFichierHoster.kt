package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.core.formatBytes
import com.jdandroid.data.Account
import com.jdandroid.data.plainApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 1fichier.com: official REST API (API key, Premium/Access required); without
 * an account the website's free flow ([resolveFree]: file page, countdown,
 * form, direct link).
 */
class OneFichierHoster internal constructor(
    /** API address; tests replace it with a local server. */
    private val base: String,
    /** Website address; tests replace it with a local server. */
    private val siteBase: String,
    /** Public link check; tests replace it with a local server. */
    private val checkLinksUrl: String,
    private val client: OkHttpClient
) : Hoster {

    constructor() : this(
        "https://api.1fichier.com/v1", "https://1fichier.com", "https://1fichier.com/check_links.pl", Http.client
    )

    override val id = "onefichier"
    override val displayName = "1fichier"
    override val accountType = AccountType.API_KEY
    override val accountHint: String
        get() = Texts.t("hoster_onefichier_account_hint")

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * All domains under which 1fichier serves files. Per the docs the file id
     * is lower case; upper case is tolerated when matching and lower-cased in
     * [normalize].
     */
    private val pattern = Regex(
        """https?://(?:www\.)?(?:1fichier\.com|alterupload\.com|cjoint\.net|desfichiers\.com|""" +
            """dfichiers\.com|megadl\.fr|mesfichiers\.org|piecejointe\.net|pjointe\.com|""" +
            """tenvoi\.com|dl4free\.com)/\?([A-Za-z0-9]{5,20})(?![A-Za-z0-9])"""
    )

    /** user/info.cgi allows one call per 5 minutes, so the result is cached. */
    private val accountCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, Result<AccountInfo>>>()
    private val accountCacheMs = 5L * 60 * 1000

    /** Credential error (invalid API key), always permanent. */
    private class AuthException(message: String) : HosterException(message, permanent = true)

    override val siteHosts: Set<String> = listOf(
        "1fichier.com", "alterupload.com", "cjoint.net", "desfichiers.com", "dfichiers.com",
        "megadl.fr", "mesfichiers.org", "piecejointe.net", "pjointe.com", "tenvoi.com", "dl4free.com"
    ).flatMap { listOf(it, "www.$it") }.toSet()

    override val supportsFree = true

    override fun matches(url: String) = pattern.containsMatchIn(url)

    /** Host of [siteBase]; domain of the cookies set by the app itself. */
    private val siteHost = siteBase.toHttpUrl().host

    /**
     * Browser user agent as in the captcha view: file page, form and file
     * request run under the same user agent and cookies.
     */
    private val browserUa: String
        get() = Http.browserUserAgent
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"

    /**
     * State of a free flow per file id: own cookie store (file page session,
     * `LG=en`), the parsed form and the time from which it may be submitted.
     * Lives only in-process.
     */
    private class FreeSession(val pageUrl: String, baseClient: OkHttpClient) {
        var form: OneFichierForm? = null
        /** Hotlink: the file page already served the file, no form needed. */
        var hotlink: ResolvedLink? = null
        var fileName: String? = null
        var fileSize = -1L
        var readyAt = 0L
        val createdAt = System.currentTimeMillis()
        val store = mutableListOf<Cookie>()
        val client: OkHttpClient = baseClient.newBuilder()
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

        val expired: Boolean get() = System.currentTimeMillis() - createdAt > SESSION_MAX_AGE_MS

        /** Cookie header for [url], null without matching cookies. */
        fun cookieHeader(url: String): String? {
            val http = url.toHttpUrlOrNull() ?: return null
            val now = System.currentTimeMillis()
            return synchronized(store) {
                store.filter { it.matches(http) && it.expiresAt > now }
                    .joinToString("; ") { "${it.name}=${it.value}" }
            }.ifBlank { null }
        }
    }

    private val freeSessions = ConcurrentHashMap<String, FreeSession>()

    private data class Resp(
        val code: Int,
        val body: String,
        val location: String?,
        val finalUrl: String,
        /** Response is a file (Content-Disposition or non-text). */
        val isFile: Boolean
    )

    private fun FreeSession.fetch(
        url: String,
        referer: String? = null,
        form: Map<String, String>? = null,
        followRedirects: Boolean = true
    ): Resp {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", browserUa)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-us,en;q=0.5")
        referer?.let { builder.header("Referer", it) }
        form?.let { f ->
            builder.post(FormBody.Builder().apply { f.forEach { (k, v) -> add(k, v) } }.build())
        }
        val c = if (followRedirects) client else {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
        return c.newCall(builder.build()).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty().lowercase()
            val attachment = resp.header("Content-Disposition")?.contains("attachment", true) == true
            // Only read pages as text, never a file (heap)
            val textual = !attachment && (
                type.isBlank() || type.startsWith("text/") || type.contains("json") ||
                    type.contains("javascript") || type.contains("xml")
                )
            val body = if (textual) runCatching { resp.peekBody(Http.MAX_TEXT_BYTES).string() }.getOrDefault("") else ""
            Resp(resp.code, body, resp.header("Location"), resp.request.url.toString(), resp.isSuccessful && !textual)
        }
    }

    private fun blockFor(block: OneFichierBlock): Nothing = when (block) {
        is OneFichierBlock.Wait -> throw WaitException(block.seconds, block.text)
        is OneFichierBlock.Permanent -> throw HosterException(block.text, permanent = true)
        is OneFichierBlock.Transient -> throw HosterException(block.text, permanent = false)
    }

    /**
     * Checks blocks and notices of a page: HTTP status (503 maintenance,
     * 403/429 block, never permanent), offline text, then the patterns in the
     * visible text ([OneFichierFreePage.classify], on the file page with
     * [downloadOffered]). Returns if nothing objects.
     */
    private fun checkPage(resp: Resp, downloadOffered: Boolean = false) {
        if (resp.code == 404 || OneFichierFreePage.isOffline(resp.body)) throw FileOfflineException()
        OneFichierFreePage.classify(OneFichierFreePage.visibleText(resp.body), downloadOffered)?.let { blockFor(it) }
        when {
            resp.code == 503 -> throw WaitException(20 * 60 + 1, Texts.t("hoster_onefichier_maintenance"))
            resp.code == 403 || resp.code == 429 -> throw WaitException(
                15 * 60 + 1, Texts.t("hoster_onefichier_temporarily_blocked", resp.code)
            )
            resp.code !in 200..299 -> throw HosterException(
                Texts.t("hoster_onefichier_file_page_unreachable", resp.code), permanent = false
            )
        }
    }

    /**
     * Free mode without an account. Unattended flow: fetch the file page (in
     * English), evaluate offline, blocks with wait time and permanent reasons;
     * if the response is already the file (hotlink) it is loaded directly.
     * Otherwise read the download form, wait out the countdown (`var count`),
     * short in-process, long as [WaitException] to the engine, and submit the
     * form; the direct link is in the response or the redirect. Browser only:
     * a captcha (only for suspicious addresses) and password-protected files,
     * both as [CaptchaRequiredException] with the file page; the user works
     * through the page there and the navigation to `a-<n>.1fichier.com/<token>`
     * is intercepted ([FreeHints.direktUrlAusBrowser]).
     */
    override suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink =
        withContext(Dispatchers.IO) {
            val link = normalize(url) ?: throw HosterException(Texts.t("hoster_onefichier_invalid_link"), true)
            val id = link.substringAfter("?")
            val pageUrl = "$siteBase/?$id&lg=en"

            hints.direktUrlAusBrowser?.takeIf { it.isNotBlank() }?.let { direct ->
                val session = freeSessions.remove(id)
                return@withContext ResolvedLink(
                    secure(direct),
                    session?.fileName,
                    session?.fileSize ?: -1,
                    headers = freeHeaders(link, direct, hints.cookies ?: session?.cookieHeader(direct))
                )
            }

            val session = freeSessions[id]?.takeUnless { it.expired || it.form == null }
                ?: startFreeSession(id, link, pageUrl)
            session.hotlink?.let { return@withContext it }

            // Countdown: short remainders in-process, long ones to the engine
            val remaining = session.readyAt - System.currentTimeMillis()
            if (remaining > MAX_INLINE_WAIT_MS) {
                throw WaitException(((remaining + 999) / 1000).toInt() + 1, Texts.t("hoster_onefichier_free_countdown"))
            }
            if (remaining > 0) delay(remaining + 500)

            val form = session.form ?: throw HosterException(Texts.t("hoster_onefichier_form_missing"), permanent = false)
            val action = form.action?.takeIf { it.startsWith("http", true) } ?: "$siteBase/?$id"
            var resp = session.fetch(action, referer = pageUrl, form = form.fields, followRedirects = false)
            var direct: String? = null
            var hops = 0
            var currentUrl = action
            while (direct == null && hops++ < 4) {
                if (resp.code in 300..399 && !resp.location.isNullOrBlank()) {
                    val target = resolveLocation(currentUrl, resp.location!!)
                    if (isDirectDownloadUrl(target)) { direct = target; break }
                    resp = session.fetch(target, referer = currentUrl, followRedirects = false)
                    currentUrl = target
                    continue
                }
                // After a redirect via GET the response may be the file; the
                // POST address itself, fetched via GET, is only the file page
                if (resp.isFile && currentUrl != action) { direct = resp.finalUrl; break }
                direct = OneFichierFreePage.directLink(resp.body)
                break
            }
            // The form is used up: the next attempt fetches the page again
            freeSessions.remove(id)
            if (direct.isNullOrBlank()) {
                checkPage(resp)
                if (OneFichierFreePage.hasCaptcha(resp.body)) {
                    throw CaptchaRequiredException(pageUrl, Texts.t("hoster_onefichier_confirm_in_browser"))
                }
                throw HosterException(Texts.t("hoster_onefichier_no_direct_link", resp.code), permanent = false)
            }
            ResolvedLink(
                secure(direct),
                session.fileName,
                session.fileSize,
                headers = freeHeaders(link, direct, session.cookieHeader(direct))
            )
        }

    /**
     * Fetches and parses the file page. Returns the session with form and
     * ready time; for a hotlink (response is the file) the finished link is in
     * [FreeSession.hotlink] and no form is needed.
     */
    private fun startFreeSession(id: String, link: String, pageUrl: String): FreeSession {
        freeSessions.remove(id)
        val session = FreeSession(pageUrl, client)
        // Force English texts so the error patterns match
        session.store.add(Cookie.Builder().name("LG").value("en").domain(siteHost).path("/").build())
        val page = session.fetch(pageUrl, referer = "$siteBase/")
        if (page.isFile) {
            // Hotlink: the owner pays the traffic, no wait
            session.hotlink = ResolvedLink(
                secure(page.finalUrl), headers = freeHeaders(link, page.finalUrl, session.cookieHeader(page.finalUrl))
            )
            return session
        }
        // With a form present the notice "only one file at a time" is not a
        // block; whether it is blocked is only shown by the form response
        val form = OneFichierFreePage.downloadForm(page.body, id)
        checkPage(page, downloadOffered = form != null)
        if (OneFichierFreePage.hasCaptcha(page.body)) {
            throw CaptchaRequiredException(pageUrl, Texts.t("hoster_onefichier_confirm_in_browser"))
        }
        if (form == null) throw HosterException(Texts.t("hoster_onefichier_no_free_offer"), permanent = false)
        if (form.needsPassword) {
            throw CaptchaRequiredException(pageUrl, Texts.t("hoster_onefichier_password_in_browser"))
        }
        session.form = form
        session.fileName = OneFichierFreePage.fileName(page.body)
        session.fileSize = OneFichierFreePage.fileSize(page.body)
        session.readyAt = System.currentTimeMillis() + OneFichierFreePage.countdownSeconds(page.body) * 1000L
        freeSessions[id] = session
        return session
    }

    private fun resolveLocation(base: String, location: String): String =
        base.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location

    /** Cleartext is disabled in the app: file server links always via HTTPS. */
    private fun secure(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /**
     * Headers for the file request in free mode: browser user agent, Referer
     * of the file page and the session cookies in case the file server
     * requires them. Cookies only for the hoster's own hosts, never for a
     * foreign [directUrl].
     */
    internal fun freeHeaders(pageUrl: String, directUrl: String, cookies: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = browserUa
        headers["Referer"] = pageUrl
        if (DirectLinks.isSiteHost(directUrl, siteHosts)) {
            cookies?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        }
        return headers
    }

    /**
     * File server address `https://a-3.1fichier.com/<token>` (no file
     * extension, hence in addition to [DirectLinks]); main domain pages never count.
     */
    override fun isDirectDownloadUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        if (host in siteHosts) return false
        return OneFichierFreePage.isFileServerUrl(url) || DirectLinks.isDirectDownloadUrl(url, siteHosts)
    }

    private companion object {
        /** Countdown remainders up to this run in-process; longer ones go to the engine as wait time. */
        const val MAX_INLINE_WAIT_MS = 90_000L
        const val SESSION_MAX_AGE_MS = 10L * 60 * 1000
    }

    /**
     * Canonical form for API calls: https://1fichier.com/?<id> (lower-case id),
     * regardless of alias domain, www. or suffixes. null = not a 1fichier link.
     */
    internal fun normalize(url: String): String? =
        pattern.find(url)?.groupValues?.get(1)?.lowercase()?.let { "https://1fichier.com/?$it" }

    private fun post(path: String, apiKey: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$base/$path")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", Http.USER_AGENT)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val (code, text) = client.newCall(request).execute().use { resp ->
            resp.code to resp.peekBody(Http.MAX_TEXT_BYTES).string()
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        val msg = json?.optString("message")?.ifBlank { null }
        httpFailure(code, msg, isJson = json != null)?.let { throw it }
        if (json!!.optString("status") == "KO") throw koFailure(msg)
        return json
    }

    /**
     * Classification of an API reply by HTTP status and message; null = no
     * error at this level. Temporary first: flood block (also comes as HTTP
     * 403), rate limit, server error, non-JSON body (Cloudflare page). Only a
     * clearly stated authentication error may disable the account
     * permanently; a blanket 403 would otherwise kill all 1fichier downloads.
     */
    internal fun httpFailure(code: Int, message: String?, isJson: Boolean): HosterException? {
        val msg = message?.ifBlank { null }
        val flood = msg?.let { it.contains("Flood", true) || it.contains("try again", true) } == true
        if (flood || code == 429 || code in 500..599) {
            return HosterException(Texts.t("hoster_onefichier_api_error", msg ?: Texts.t("hoster_too_many_requests", code)), permanent = false)
        }
        if (code == 401 || (code == 403 && msg?.contains("Not authenticated", true) == true)) {
            return AuthException(Texts.t("hoster_onefichier_api_error", msg ?: Texts.t("hoster_not_authenticated", code)))
        }
        if (code == 403) {
            return HosterException(Texts.t("hoster_onefichier_api_error", msg ?: Texts.t("hoster_access_blocked")), permanent = false)
        }
        if (!isJson) return HosterException(Texts.t("hoster_onefichier_unexpected_response", code), permanent = false)
        return null
    }

    /**
     * Classification of an API reply with status=KO. Flood/rate limit is
     * temporary; missing/deleted file and missing Premium/Access ("You must
     * be a Premium/Access user") are permanent: a free account does not get
     * through on a later attempt either.
     */
    internal fun koFailure(message: String?): HosterException {
        val m = message?.ifBlank { null } ?: Texts.t("hoster_unknown_error")
        if (m.contains("Not authenticated", true)) return AuthException(Texts.t("hoster_onefichier_api_error", m))
        val transient = m.contains("Flood", true) || m.contains("try again", true)
        val permanent = !transient && (
            m.contains("not found", true) ||
                m.contains("deleted", true) ||
                m.contains("no such", true) ||
                m.contains("not allowed", true) ||
                m.contains("Resource not", true) ||
                m.contains("premium", true) ||
                m.contains("must be", true)
        )
        return HosterException(Texts.t("hoster_onefichier_api_error", m), permanent = permanent)
    }

    /**
     * Public link check without an API key: check_links.pl returns per line
     * "url;filename;size" for an existing file and "url;;NOT FOUND" or
     * "url;BAD LINK" otherwise. The last field decides, not the column count,
     * which keeps the parsing robust against format variants.
     */
    internal fun parseCheckLine(line: String): LinkInfo {
        val parts = line.trim().split(';').map { it.trim() }
        if (parts.size < 2) return LinkInfo(online = null, note = Texts.t("hoster_unexpected_response"))
        val status = parts.last()
        val offline = listOf("NOT FOUND", "BAD LINK", "DELETED").any { status.contains(it, true) }
        if (offline) return LinkInfo(online = false, note = status.lowercase().replaceFirstChar { it.uppercase() })
        if (parts.size < 3) return LinkInfo(online = null, note = Texts.t("hoster_unexpected_response"))
        val name = parts[1].ifBlank { null }
        val size = parts[2].toLongOrNull() ?: -1
        return LinkInfo(online = true, fileName = name, fileSize = size)
    }

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            val link = normalize(url)
                ?: return@withContext LinkInfo(online = null, note = Texts.t("hoster_onefichier_invalid_link"))
            val form = okhttp3.FormBody.Builder().add("links[]", link).build()
            val request = Request.Builder()
                .url(checkLinksUrl)
                .header("User-Agent", Http.USER_AGENT)
                .post(form)
                .build()
            val text = client.newCall(request).execute().use { resp ->
                resp.peekBody(Http.MAX_TEXT_BYTES).string()
            }
            val line = text.lines().firstOrNull { it.contains("1fichier.com") }
                ?: return@withContext LinkInfo(online = null, note = Texts.t("hoster_onefichier_check_no_response"))
            parseCheckLine(line)
        }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val key = account.plainApiKey ?: throw HosterException(Texts.t("hoster_no_api_key"), true)
        val now = System.currentTimeMillis()
        // Cache failures too: 1fichier allows user/info only every 5 minutes
        // and the accounts view asks every minute. A repeated failed attempt
        // would otherwise trigger the flood block.
        accountCache[account.id]?.let { (at, cached) ->
            if (now - at < accountCacheMs) return@withContext cached.getOrThrow()
        }
        val result = runCatching { fetchAccount(key) }
        accountCache[account.id] = now to result
        result.getOrThrow()
    }

    private fun fetchAccount(key: String): AccountInfo {
        val json = post("user/info.cgi", key, JSONObject())
        // "offer" may be a number (>0 = paying) or text ("Premium"/"Access"/"Free")
        val offerRaw = json.opt("offer")?.toString()?.trim().orEmpty()
        val endText = json.optString("subscription_end")
        val end = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(endText)?.time ?: 0L
        }.getOrDefault(0L)
        val premium = when {
            offerRaw.equals("Premium", true) || offerRaw.equals("Access", true) -> true
            offerRaw.toIntOrNull()?.let { it > 0 } == true -> true
            end > System.currentTimeMillis() -> true
            else -> false
        }
        // 1fichier does not limit Premium/Access downloads; the CDN credit (GB)
        // is only a hint. "cdn" is just the 0/1 flag, the amount is in
        // available_credits_in_gb.
        val cdnGb = json.optDouble("available_credits_in_gb", -1.0).takeIf { it >= 0 }
        return AccountInfo(
            valid = true,
            premiumUntil = end,
            trafficLeft = -1,
            trafficUnlimited = premium,
            statusText = withCdnCredit(if (premium) "Premium/Access" else freeStatusText, cdnGb)
        )
    }

    /** Appends the CDN credit (reported by the API in GB) in binary units. */
    internal fun withCdnCredit(status: String, cdnGb: Double?): String =
        if (cdnGb != null && cdnGb > 0) {
            Texts.t("hoster_status_cdn_credit", status, formatBytes((cdnGb * (1L shl 30)).toLong()))
        } else status

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            val key = account?.plainApiKey ?: throw HosterException(Texts.t("hoster_onefichier_api_key_required"), permanent = true)
            val link = normalize(url) ?: throw HosterException(Texts.t("hoster_onefichier_invalid_link"), true)
            var fileName: String? = null
            var size = -1L
            try {
                val info = post("file/info.cgi", key, JSONObject().put("url", link))
                fileName = info.optString("filename").ifBlank { null }
                size = info.optLong("size", -1)
            } catch (e: AuthException) {
                // Invalid API key: get_token would fail the same way
                throw e
            } catch (_: Exception) {
                // Name/size are optional; an error here does not prevent the download
            }
            val token = post("download/get_token.cgi", key, JSONObject().put("url", link))
            val direct = token.optString("url")
            if (direct.isBlank()) throw HosterException(Texts.t("hoster_onefichier_no_download_url"), true)
            // The checksum 1fichier returns is Whirlpool (128 hex), not
            // SHA-1/MD5, so it is unusable for the integrity check.
            ResolvedLink(direct, fileName, size, hash = null)
        }
}
