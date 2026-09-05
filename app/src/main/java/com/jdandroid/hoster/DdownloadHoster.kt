package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.core.FileNames
import com.jdandroid.data.Account
import com.jdandroid.data.plainApiKey
import com.jdandroid.data.plainCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ddownload.com, an XFileSharing hoster. Login via username/password (session
 * cookie), premium direct download via the two-step download form. Free
 * downloads go through [DdownloadFree]: blocks and wait times are read from
 * the page, the Cloudflare Turnstile in the download form is only solvable in
 * the embedded browser. The account page is parsed by [DdownloadAccountPage].
 */
class DdownloadHoster internal constructor(
    /** Website address; tests replace it with a local server. */
    internal val siteBase: String,
    /** API address; tests replace it with a local server. */
    private val apiBase: String = "https://api-v2.ddownload.com/api",
    /** Client for API calls (the website uses one cookie store per account). */
    private val apiClient: OkHttpClient = Http.client
) : Hoster {

    constructor() : this("https://ddownload.com")

    override val id = "ddownload"
    override val displayName = "ddownload"
    // The web login is protected by Cloudflare Turnstile and cannot be solved
    // headlessly, hence two paths: API key (recommended, no captcha) or login
    // in the embedded browser with session takeover.
    override val accountType = AccountType.API_KEY
    override val accountHint: String
        get() = Texts.t("hoster_ddownload_account_hint")
    override val webLoginUrl = "https://ddownload.com/login.html"

    override val supportsFree = true

    /** Host of [siteBase]; domain of the session cookies taken over from the browser. */
    private val siteHost = siteBase.toHttpUrl().host

    /**
     * File codes are exactly 12 chars [a-z0-9]; the lookahead prevents longer
     * paths (e.g. /register.html) from being read as a code.
     */
    private val pattern =
        Regex("""https?://(?:www\.)?(?:ddownload\.com|ddl\.to)/(?:f/|d/)?([a-z0-9]{12})(?![A-Za-z0-9])""")

    /**
     * Direct links live on file servers (subdomain other than www), never on
     * the main domain and never under /cgi-bin/ (tracker.cgi lives there).
     */
    private val fileServerRegex =
        Regex("""https?://(?!www\.)[a-z0-9-]+\.(?:ddownload\.com|ddl\.to)(?::\d+)?/[^\s"'<>]+""")

    /** Main domains that never serve a file (pages only). */
    override val siteHosts = setOf("ddownload.com", "www.ddownload.com", "ddl.to", "www.ddl.to")

    internal val browserUa: String get() = Http.browserUa

    /** One cookie store (session) per account id. */
    private val cookieJars = java.util.concurrent.ConcurrentHashMap<Long, MemoryCookieJar>()
    private val clients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    override fun matches(url: String) = pattern.containsMatchIn(url)

    internal fun fileCode(url: String): String =
        pattern.find(url)?.groupValues?.get(1)
            ?: throw HosterException(Texts.t("hoster_ddownload_invalid_link"), true)

    private fun jarFor(accountId: Long): MemoryCookieJar = cookieJars.getOrPut(accountId) { MemoryCookieJar() }

    internal fun clientFor(accountId: Long): OkHttpClient = clients.getOrPut(accountId) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(jarFor(accountId))
            .build()
    }

    internal fun OkHttpClient.fetch(
        url: String,
        form: Map<String, String>? = null,
        referer: String? = null,
        followRedirects: Boolean = true
    ): PageResponse = fetchPage(url, referer, form, acceptLanguage = "de,en;q=0.8", followRedirects = followRedirects)

    /** Detects Cloudflare/WAF blocks so the message does not read "wrong password". */
    internal fun checkBlocked(resp: PageResponse) {
        val blocked = resp.code == 403 || resp.code == 503 ||
            resp.body.contains("Just a moment", true) ||
            resp.body.contains("cf-browser-verification", true) ||
            resp.body.contains("Attention Required", true) ||
            resp.body.contains("Enable JavaScript and cookies", true)
        if (blocked) {
            throw HosterException(Texts.t("hoster_ddownload_cloudflare_blocked", resp.code), permanent = false)
        }
    }

    /** The logout link is the reliable sign of being logged in, not a cookie name. */
    private fun isLoggedIn(html: String): Boolean =
        html.contains("op=logout", true) ||
            (html.contains("?op=my_account", true) && html.contains("Account type", true))

    /**
     * Returns the client and the account page HTML based on the session
     * cookies taken over from the browser. A headless form login is impossible
     * because of the Turnstile captcha.
     */
    private fun sessionAndAccountPage(account: Account): Pair<OkHttpClient, String> {
        val raw = account.plainCookies
        if (raw.isNullOrBlank()) {
            throw HosterException(Texts.t("hoster_ddownload_no_login"), permanent = true)
        }
        val client = clientFor(account.id)
        seedCookies(account.id, raw)

        var page = client.fetch("$siteBase/?op=my_account", referer = siteBase)
        checkBlocked(page)
        if (page.code !in 200..299) {
            // Server error or maintenance page: temporary, no reason to disable the account
            throw HosterException(Texts.t("hoster_ddownload_account_page_unreachable", page.code), permanent = false)
        }
        if (!isLoggedIn(page.body)) {
            // The cookie store may hold a session the server "deleted"
            // (Set-Cookie with an expiry in the past). Start over once with the
            // stored browser cookies before treating the session as expired.
            seedCookies(account.id, raw, force = true)
            page = client.fetch("$siteBase/?op=my_account", referer = siteBase)
            checkBlocked(page)
            if (page.code !in 200..299) {
                throw HosterException(Texts.t("hoster_ddownload_account_page_unreachable", page.code), permanent = false)
            }
        }
        if (!isLoggedIn(page.body)) {
            throw HosterException(Texts.t("hoster_ddownload_session_expired"), permanent = true)
        }
        return client to page.body
    }

    /** Seeds the OkHttp cookie store with the cookie string from the browser. */
    private fun seedCookies(accountId: Long, raw: String, force: Boolean = false) {
        val cookies = raw.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty()) null else Cookie.Builder().name(name).value(value).domain(siteHost).path("/").build()
        }
        jarFor(accountId).seed(cookies, force)
    }

    /** Collects all hidden fields of a form (attribute order does not matter). */
    internal fun hiddenInputs(html: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val t = tag.value
            // Remember the opening quote: an apostrophe in the value
            // ("It's.a.file.mkv") must not cut the value short
            val name = Regex("""\bname=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: return@forEach
            val value = Regex("""\bvalue=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(2) ?: ""
            result[name] = value
        }
        return result
    }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val key = account.plainApiKey?.takeIf { it.isNotBlank() }
        if (key != null) return@withContext checkViaApi(key)

        val (_, html) = sessionAndAccountPage(account)
        val (expire, premium, tier) = DdownloadAccountPage.status(DdownloadAccountPage.visibleText(html))

        // With an API key on the account page the API reports the quota more
        // reliably than the HTML (premium_traffic_left). Premium counts if the
        // page OR the API says so; with an unclear API date format the page is
        // the more reliable source.
        DdownloadAccountPage.apiKeyFromPage(html)?.let { key ->
            runCatching { checkViaApi(key) }.getOrNull()?.let { viaApi ->
                if (viaApi.trafficLeft >= 0 || viaApi.trafficUnlimited) {
                    val apiPremium = viaApi.statusText.startsWith("Premium")
                    return@withContext if (premium) {
                        viaApi.copy(
                            premiumUntil = if (viaApi.premiumUntil > 0) viaApi.premiumUntil else expire,
                            statusText = if (apiPremium) viaApi.statusText.replaceFirst("Premium", tier) else tier
                        )
                    } else viaApi
                }
            }
        }

        val traffic = DdownloadAccountPage.plausibleTraffic(DdownloadAccountPage.parseTraffic(html))
        val trafficTotal = when {
            traffic.unlimited -> -1L
            traffic.total > 0 -> traffic.total
            premium && traffic.left >= 0 -> maxOf(DdownloadAccountPage.DAILY_QUOTA, traffic.left)
            else -> -1L
        }

        AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = traffic.left,
            trafficTotal = trafficTotal,
            trafficUnlimited = traffic.unlimited,
            statusText = (if (premium) tier else freeStatusText).let { status ->
                if (traffic.left < 0 && !traffic.unlimited) Texts.t("hoster_status_quota_unreadable", status) else status
            }
        )
    }

    /** Account check via the official API (no captcha). */
    private fun checkViaApi(key: String): AccountInfo {
        val json = apiCall("account/info", mapOf("key" to key))
        // Status 200 without "result" is a changed response format, not an
        // invalid key; key errors are already permanent in apiCall.
        val result = json.optJSONObject("result")
            ?: throw HosterException(Texts.t("hoster_ddownload_unexpected_api_response"), permanent = false)
        val expire = DdownloadAccountPage.parseExpire(result.opt("premium_expire")?.toString())
        // Per API docs "premium_traffic_left" (in MB, e.g. 102400 = 100 GB) is
        // the remaining premium daily quota; "traffic_left"/"traffic_used"
        // refer to free traffic and are irrelevant for premium.
        val rawPremiumLeft = result.opt("premium_traffic_left")?.toString()?.trim().orEmpty()
        val unlimited = rawPremiumLeft.contains("unlimited", true) || rawPremiumLeft == "inf"
        val premiumLeft = rawPremiumLeft.toDoubleOrNull()
        // Premium: a valid expiry date, or, if the API reports the date in an
        // unknown format, an existing premium quota (a free account has none).
        val premium = expire > System.currentTimeMillis() ||
            (expire == 0L && (unlimited || (premiumLeft ?: 0.0) > 0))
        val left = when {
            unlimited -> -1L
            premiumLeft != null && premiumLeft >= 0 ->
                DdownloadAccountPage.plausibleQuota(DdownloadAccountPage.quotaToBytes(premiumLeft))
            else -> {
                // Older API without premium_traffic_left: traffic_left as fallback
                result.opt("traffic_left")?.toString()?.trim()?.toDoubleOrNull()
                    ?.let { DdownloadAccountPage.quotaToBytes(it) } ?: -1L
            }
        }
        val total = when {
            unlimited -> -1L
            premium && left >= 0 -> maxOf(DdownloadAccountPage.DAILY_QUOTA, left)
            else -> -1L
        }
        return AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = left,
            trafficTotal = total,
            trafficUnlimited = unlimited,
            statusText = if (premium) "Premium" else freeStatusText
        )
    }

    private fun apiCall(path: String, params: Map<String, String>): org.json.JSONObject {
        val query = params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        val (code, text) = apiClient.newCall(
            okhttp3.Request.Builder().url("$apiBase/$path?$query")
                .header("User-Agent", browserUa).build()
        ).execute().use { it.code to it.peekBody(Http.MAX_TEXT_BYTES).string() }
        // Cloudflare/error pages are not JSON: temporary, not "account invalid"
        val json = runCatching { org.json.JSONObject(text) }.getOrElse {
            throw HosterException(Texts.t("hoster_ddownload_api_not_json", code), permanent = false)
        }
        val status = json.optInt("status")
        if (status != 200) throw apiFailure(status, json.optString("msg"))
        return json
    }

    /**
     * Classification of an API reply with status != 200. Permanent only what
     * the text identifies as such (file gone, key invalid); daily limit,
     * blocks and server errors are temporary.
     */
    internal fun apiFailure(status: Int, message: String): HosterException {
        val msg = message.ifBlank { Texts.t("hoster_http_status", status) }
        val permanent = status == 404 ||
            msg.contains("not found", true) || msg.contains("invalid key", true) ||
            msg.contains("wrong key", true) || msg.contains("no such", true)
        return HosterException(Texts.t("hoster_ddownload_api_error", msg), permanent = permanent)
    }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            if (account == null) throw HosterException(Texts.t("hoster_ddownload_premium_required"), true)
            val code = fileCode(url)
            account.plainApiKey?.takeIf { it.isNotBlank() }?.let { key ->
                return@withContext resolveViaApi(key, code)
            }
            val (client, accountHtml) = sessionAndAccountPage(account)
            // With an API key on the account page the API is the safest route
            // to the direct link (no form, no redirect chain)
            DdownloadAccountPage.apiKeyFromPage(accountHtml)?.let { key ->
                runCatching { resolveViaApi(key, code) }
                    .onFailure { if (it is FileOfflineException) throw it }
                    .getOrNull()?.let { return@withContext it }
            }
            val pageUrl = "$siteBase/$code"

            var page = client.fetch(pageUrl, referer = siteBase)
            checkBlocked(page)
            // Account with "Direct Downloads": the page redirects straight to
            // the file; the client followed it, the final address is the link
            if (page.isFile && page.finalUrl.toHttpUrlOrNull()?.host?.lowercase() !in siteHosts) {
                return@withContext ResolvedLink(page.finalUrl, FileNames.fromDisposition(page.contentDisposition))
            }
            checkOffline(page.body)
            // Remember the name from the file page: after the redirect chain
            // the page text is a redirect without content
            val pageName = pageFileName(page.body)

            var direct = extractDirectLink(page.body)
            var formsSent = 0
            var hops = 0
            var currentUrl = pageUrl
            while (direct == null && hops++ < 6) {
                if (page.code in 300..399 && !page.location.isNullOrBlank()) {
                    // Redirect: if it points to a file, that is the direct link;
                    // otherwise follow the next page (without loading the file).
                    // Relative targets resolve against the last fetched address.
                    val target = resolveLocation(currentUrl, page.location!!)
                    if (isFileServerUrl(target)) { direct = target; break }
                    page = client.fetch(target, referer = currentUrl, followRedirects = false)
                    currentUrl = target
                    checkBlocked(page)
                    // Response is already the file (address without extension,
                    // e.g. dl.cgi/<token>): the body was not read
                    if (page.code in 200..299 && page.isFile) { direct = target; break }
                    direct = extractDirectLink(page.body)
                    continue
                }
                if (formsSent >= 2) break
                // Submit the download form (op=download2, method_premium)
                // without following redirects: XFileSharing answers with a
                // redirect whose Location is already the direct link.
                val form = downloadForm(page.body, code)
                formsSent++
                page = client.fetch(pageUrl, form = form, referer = pageUrl, followRedirects = false)
                currentUrl = pageUrl
                checkBlocked(page)
                if (page.code in 200..299 && page.isFile) {
                    // The file itself came back on the POST: its address fetched
                    // via GET is only the file page, so there is no usable link
                    throw HosterException(Texts.t("hoster_ddownload_no_direct_link", page.code), permanent = false)
                }
                direct = extractDirectLink(page.body)
                if (direct == null && page.code !in 300..399) checkOffline(page.body)
            }
            if (direct.isNullOrBlank()) {
                val text = DdownloadAccountPage.visibleText(page.body)
                val limitReached = Regex("""(?i)download limit|reached the|limit reached|too many|try again later""")
                    .containsMatchIn(text)
                val freeMode = page.body.contains("countdown", true) ||
                    Regex("""(?i)name=["']method_free["'][^>]*value=["'][^"']+""").containsMatchIn(page.body)
                val hint = when {
                    limitReached -> Texts.t("hoster_hint_limit_reached")
                    text.contains("premium", true) && text.contains("only", true) ->
                        Texts.t("hoster_hint_premium_only")
                    freeMode -> Texts.t("hoster_hint_free_wait")
                    page.code in 300..399 -> Texts.t("hoster_hint_redirect_without_file")
                    else -> Texts.t("hoster_hint_unexpected_response")
                }
                // Form errors about the "rand" id are transient: a reloaded page
                // yields a fresh one
                val transientForm = DdownloadFreePage.isExpiredSession(page.body) ||
                    DdownloadFreePage.isSkippedCountdown(page.body)
                throw HosterException(
                    Texts.t("hoster_ddownload_no_direct_link_hint", page.code, hint),
                    permanent = !transientForm && resolveFailurePermanent(page.code, limitReached)
                )
            }
            val fileName = pageName ?: pageFileName(page.body)
                ?: direct.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }
            ResolvedLink(direct, fileName)
        }

    /** Free flow (blocks, countdown, Turnstile), see [DdownloadFree]. */
    internal val free = DdownloadFree(this)

    override suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink = free.resolve(url, hints)

    /** Cookie header from the OkHttp store for [url]; null without matching cookies. */
    internal fun cookieHeader(accountId: Long, url: String): String? = cookieJars[accountId]?.cookieHeader(url)

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            val code = fileCode(url)
            val key = account?.plainApiKey
            if (!key.isNullOrBlank()) {
                val info = apiCall("file/info", mapOf("key" to key, "file_code" to code))
                    .optJSONArray("result")?.optJSONObject(0)
                    ?: return@withContext LinkInfo(online = null, note = Texts.t("hoster_api_no_response"))
                val status = info.optInt("status")
                return@withContext if (status != 200) {
                    // 404 = not found; any other status is "not online" as well
                    val note = info.optString("msg").ifBlank { null }
                        ?: if (status == 404) Texts.t("hoster_file_not_found") else Texts.t("hoster_status_code", status)
                    LinkInfo(online = false, note = note)
                } else {
                    LinkInfo(
                        online = true,
                        fileName = info.optString("name").ifBlank { null },
                        fileSize = info.optLong("size", -1)
                    )
                }
            }
            // Without an API key: parse the public file page (no login needed),
            // with the browser user agent or Cloudflare only serves a challenge.
            val resp = clientFor(0L).fetch("$siteBase/$code")
            val html = resp.body
            if (resp.code == 404 || html.contains("File Not Found", true) || html.contains("No such file", true)) {
                return@withContext LinkInfo(online = false, note = Texts.t("hoster_file_not_found"))
            }
            if (html.contains("Just a moment", true) || html.contains("cf-challenge", true) ||
                html.contains("challenge-platform", true) || html.contains("Attention Required", true)
            ) {
                return@withContext LinkInfo(online = null, note = Texts.t("hoster_cloudflare_check_unknown"))
            }
            // An error page (5xx, 403) is no proof of "online"
            if (resp.code !in 200..299) {
                return@withContext LinkInfo(online = null, note = Texts.t("hoster_http_status_unknown", resp.code))
            }
            LinkInfo(
                online = true,
                fileName = pageFileName(html),
                fileSize = pageFileSize(html)
            )
        }

    /**
     * File name from an XFileSharing file page. Order: the heading with class
     * dk-dl-name (current layout), then the fname field, finally the title,
     * where ddownload replaces dots with spaces ("Download scn smps8 S37E02
     * rar"), so it only counts if it still carries an extension.
     */
    internal fun pageFileName(html: String): String? {
        Regex(
            """<h[12]\b[^>]*class=["'][^"']*\bdk-dl-name\b[^"']*["'][^>]*>\s*([^<]+?)\s*</h[12]>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.removePrefix("Download ")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return com.jdandroid.core.ArchiveNames.repairName(it) }
        Regex("""name=["']fname["']\s+value=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("""value=(["'])(.*?)\1\s+name=["']fname["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        // The site suffix is separated by " - " or " | "; a hyphen inside the
        // name ("scn-smps8-S37E02 rar") is part of it
        return Regex("""<title>([^<]+?)(?:\s+[-|–]\s+.*)?</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.removePrefix("Download ")?.trim()
            ?.let { com.jdandroid.core.ArchiveNames.repairName(it) }
            ?.takeIf {
                it.isNotBlank() && !it.contains("ddownload", true) &&
                    Regex("""\.[A-Za-z0-9]{1,10}$""").containsMatchIn(it)
            }
    }

    /** Size such as "1.2 GB" from the file page. */
    internal fun pageFileSize(html: String): Long {
        // Visible text only, starting at the file name: ads and quota figures
        // ("200 GB traffic per day") come before it
        val text = DdownloadAccountPage.visibleText(html)
        val start = pageFileName(html)?.let { text.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(KB|MB|GB|TB)\b""", RegexOption.IGNORE_CASE).find(text, start)
            ?: return -1
        return DdownloadAccountPage.toBytes(m.groupValues[1], m.groupValues[2])
    }

    /** Direct link via the API (premium required, no captcha). */
    private fun resolveViaApi(key: String, code: String): ResolvedLink {
        var fileName: String? = null
        runCatching {
            val info = apiCall("file/info", mapOf("key" to key, "file_code" to code))
                .optJSONArray("result")?.optJSONObject(0)
            fileName = info?.optString("name")?.ifBlank { null }
            if (info?.optInt("status") == 404) throw FileOfflineException()
        }.onFailure { if (it is HosterException && it.permanent) throw it }
        val result = apiCall("file/direct_link", mapOf("key" to key, "file_code" to code))
            .optJSONObject("result")
            ?: throw HosterException(Texts.t("hoster_ddownload_no_download_url"), true)
        val direct = result.optString("url")
        if (direct.isBlank()) {
            throw HosterException(Texts.t("hoster_ddownload_no_download_url_premium"), true)
        }
        return ResolvedLink(direct, fileName, result.optLong("size", -1))
    }

    private fun checkOffline(html: String) {
        if (html.contains("File Not Found", true) ||
            html.contains("file was deleted", true) ||
            html.contains("No such file", true)
        ) {
            throw FileOfflineException()
        }
    }

    /**
     * Fields of the download form. The XFileSharing field names are known, so
     * the form is built by hand if needed: for logged-in users the page
     * sometimes lacks a complete form, and resolving must not fail on that.
     */
    internal fun downloadForm(html: String, code: String): Map<String, String> {
        val block = formBlock(html) ?: html
        val inputs = hiddenInputs(block).toMutableMap()
        inputs["op"] = "download2"
        inputs["id"] = inputs["id"]?.ifBlank { code } ?: code
        inputs.putIfAbsent("rand", "")
        inputs.putIfAbsent("referer", "")
        inputs["method_free"] = ""
        inputs["method_premium"] = "1"
        return inputs
    }

    /** The form with the download operation, so no foreign fields are sent along. */
    internal fun formBlock(html: String): String? =
        Regex("""<form\b[^>]*>.*?</form>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .firstOrNull { it.value.contains("op\"", true) && it.value.contains("download", true) }
            ?.value

    /**
     * True for addresses with a file name on a host other than the main
     * domain: file servers (subdomain, also with a port like :183, also under
     * /cgi-bin/dl.cgi/) and foreign CDN hosts the form response may redirect
     * to. Page links, tracker.cgi, relative redirects and /login.html fail the
     * host and extension checks.
     */
    internal fun isFileServerUrl(url: String): Boolean {
        if (DirectLinks.isDirectDownloadUrl(url, siteHosts)) return true
        // File server subdomain with a download path, even without an extension
        // (e.g. /cgi-bin/dl.cgi/<token>): this is how the free form answers
        val http = url.toHttpUrlOrNull() ?: return false
        val host = http.host.lowercase()
        if (host in siteHosts || host in serviceHosts) return false
        if (!Regex("""^[a-z0-9-]+\.(?:ddownload\.com|ddl\.to)$""").matches(host)) return false
        return Regex("""^/(?:cgi-bin/dl\.cgi|d|files)/[^/]+""").containsMatchIn(http.encodedPath) &&
            http.pathSegments.lastOrNull().orEmpty().isNotEmpty()
    }

    /** Subdomains serving pages or the API, never files. */
    private val serviceHosts = setOf("my.ddownload.com", "api-v2.ddownload.com", "my.ddl.to")

    override fun isDirectDownloadUrl(url: String): Boolean = isFileServerUrl(url)

    /** Resolves a Location header (possibly relative) against the page address. */
    internal fun resolveLocation(base: String, location: String): String =
        base.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location

    /**
     * Direct link from the HTML. Only file server addresses count: matching on
     * "contains download" would hit every address of the site itself
     * ("ddownload.com"), and tracker.cgi on the main domain is not a file.
     */
    internal fun extractDirectLink(html: String): String? =
        fileServerRegex.findAll(html)
            .map { it.value.trimEnd('\\', '"', '\'', ')').replace("&amp;", "&") }
            .firstOrNull { isFileServerUrl(it) }

    /**
     * No direct link: permanent only without a limit hint and without a server
     * error. 5xx and 429 (throttling) are temporary; checkBlocked() only
     * catches 403/503.
     */
    internal fun resolveFailurePermanent(code: Int, limitReached: Boolean): Boolean =
        !limitReached && code !in 500..599 && code != 429

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
}
