package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * rapidgator.net: official API v2 for premium accounts; without an account the
 * website's free flow ([resolveFree]: timer, unlock, captcha).
 */
class RapidgatorHoster internal constructor(
    /** API address; tests replace it with a local server. */
    private val base: String,
    /** Website address; tests replace it with a local server. */
    private val siteBase: String,
    private val client: OkHttpClient
) : Hoster {

    constructor() : this("https://rapidgator.net/api/v2", "https://rapidgator.net", Http.client)

    override val id = "rapidgator"
    override val displayName = "Rapidgator"
    override val accountType = AccountType.USERNAME_PASSWORD
    override val accountHint: String
        get() = Texts.t("hoster_rapidgator_account_hint")

    private val pattern = Regex("""https?://(?:www\.)?(?:rapidgator\.net|rg\.to)/file/\S+""")

    private val api = RapidgatorApi(base, client)

    override val siteHosts = setOf("rapidgator.net", "www.rapidgator.net", "rg.to", "www.rg.to")

    override val supportsFree = true

    override fun matches(url: String) = pattern.containsMatchIn(url)

    /** Host of [siteBase]; domain of the cookies set by the app itself. */
    private val siteHost = siteBase.toHttpUrl().host

    /** Captcha page: the user solves the Turnstile there in the embedded browser. */
    private val captchaPageUrl get() = "$siteBase/download/captcha"

    /**
     * State of a free flow per file id: own cookie store (PHPSESSID, __token,
     * sdata__), timer id and the time from which the link may be unlocked.
     * Lives only in-process; after a restart the flow starts over.
     */
    private class FreeSession(val pageUrl: String, baseClient: OkHttpClient) :
        FreeBrowserSession(baseClient, SESSION_MAX_AGE_MS) {
        lateinit var vars: RapidgatorFreeVars
        var fileName: String? = null
        var fileSize = -1L
        var hash: String? = null
        var sid: String? = null
        var readyAt = 0L
        var linkReady = false
    }

    private val freeSessions = FreeSessions<FreeSession> { it.expired }

    /** Restarts per file when the server rejects the timer or the state. */
    private val restarts = ConcurrentHashMap<String, Int>()

    private fun FreeSession.fetch(
        url: String,
        referer: String? = null,
        ajax: Boolean = false,
        followRedirects: Boolean = true
    ): PageResponse = client.fetchPage(url, referer, ajax = ajax, followRedirects = followRedirects)

    private fun waitFor(block: RapidgatorBlock): Nothing = when (block) {
        is RapidgatorBlock.Wait -> throw WaitException(block.seconds, block.text)
        is RapidgatorBlock.Permanent -> throw HosterException(block.text, permanent = true)
        RapidgatorBlock.Restart -> throw HosterException(Texts.t("hoster_rapidgator_restart_flow"), permanent = false)
    }

    /**
     * Free mode without an account. Unattended flow: fetch the file page
     * (offline, premium limits, blocks with wait time), start the timer via
     * AjaxStartTimer and hand the wait (`secs`, usually 180 s) to the engine as
     * [WaitException]; on the next attempt unlock the link via
     * AjaxGetDownloadLink and check the captcha page. If it shows the Turnstile
     * form, the session cookies go to the captcha view with the
     * [CaptchaRequiredException] and the user solves the captcha there; the
     * captcha view intercepts the navigation to the file server
     * (`pr<N>.rapidgator.net//?r=download/index&session_id=…`) and returns it
     * as [FreeHints.direktUrlAusBrowser].
     *
     * All steps run over the same IP, cookies and browser user agent: the
     * unlock (`sdata__`) is bound to the IP, and fetching too early invalidates
     * the timer (then start over).
     */
    override suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink =
        withContext(Dispatchers.IO) {
            val (id, nameSegment) = RapidgatorFreePage.fileIdAndName(url)
                ?: throw HosterException(Texts.t("hoster_rapidgator_invalid_link"), true)
            val pageUrl = "$siteBase/file/$id" + (nameSegment?.let { "/$it" } ?: "")

            hints.browserLink()?.let { direct ->
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
                val session = freeSessions[id] ?: startFreeSession(id, pageUrl)
                awaitCountdown(session.readyAt, Texts.t("hoster_rapidgator_free_wait"))

                if (!session.linkReady) {
                    val reply = ajax(session, session.vars.getDownloadUrl, "sid" to (session.sid ?: ""))
                    when (reply.state) {
                        "done" -> session.linkReady = true
                        else -> {
                            val block = reply.code?.let { RapidgatorFreePage.classify(it) }
                            if (block != null && block !is RapidgatorBlock.Restart) waitFor(block)
                            // Timer not accepted (too early, state lost): start over
                            restart(id, Texts.t("hoster_rapidgator_unlock_rejected", reply.code ?: reply.state))
                            continue
                        }
                    }
                }

                // Captcha page: with lost state (other IP, expired) it redirects
                // back to the file page, then start over
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
                RapidgatorFreePage.directLink(page.body)?.let { DirectLinks.https(it) }?.let { direct ->
                    // No captcha required: the direct link is already there
                    freeSessions.remove(id)
                    restarts.remove(id)
                    return@withContext ResolvedLink(
                        direct, session.fileName, session.fileSize, session.hash,
                        headers = freeHeaders(direct, session.cookieHeader(direct))
                    )
                }
                if (RapidgatorFreePage.hasCaptchaForm(page.body)) {
                    // Session cookies go along: the captcha view sets them when it opens
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
     * Fetches and parses the file page and starts the timer. Never returns
     * normally: the timer's wait goes to the engine as [WaitException] and the
     * session stays stored for the next attempt.
     */
    private fun startFreeSession(id: String, pageUrl: String): Nothing {
        freeSessions.remove(id)
        val session = FreeSession(pageUrl, client)
        // Force English texts so the error patterns match
        session.jar.add(Cookie.Builder().name("lang").value("en").domain(siteHost).path("/").build())
        val page = session.fetch(pageUrl, referer = "$siteBase/")
        // Unknown id: 302 to /article/premium; rg.to redirects to rapidgator.net
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

    /** Discards the state and starts over; gives up for now after too many restarts. */
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
     * Headers for the file request: browser user agent and Referer of the
     * captcha page as in the browser, plus the cookies (sdata__ applies to all
     * subdomains). Cookies only for the hoster's own hosts, never for a
     * foreign [directUrl].
     */
    internal fun freeHeaders(directUrl: String, cookies: String?): Map<String, String> =
        freeFileHeaders(captchaPageUrl, directUrl, cookies, siteHosts)

    /**
     * File server address after the captcha:
     * `https://pr5.rapidgator.net//?r=download/index&session_id=…` (no file
     * extension, hence in addition to [DirectLinks]). Main domain pages never count.
     */
    override fun isDirectDownloadUrl(url: String): Boolean {
        if (DirectLinks.isDirectDownloadUrl(url, siteHosts)) return true
        val m = Regex("""^https?://([^/?#]+)/[^?#]*\?(?:[^#]*&)?r=download/index(?:&|$)""", RegexOption.IGNORE_CASE)
            .find(url) ?: return false
        if (m.groupValues[1].lowercase() in siteHosts) return false
        return Regex("""[?&]session_id=[A-Za-z0-9]+""").containsMatchIn(url)
    }

    private companion object {
        const val SESSION_MAX_AGE_MS = 2L * 60 * 60 * 1000
        const val MAX_RESTARTS = 3
    }

    /** file_id (hash) from the Rapidgator URL: .../file/<id>[/name.html] */
    private fun fileId(url: String): String =
        Regex("""/file/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: throw HosterException(Texts.t("hoster_rapidgator_invalid_link"), true)

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        // With a cached token user/info suffices; after a login its response
        // already contains the user object, so a second call is unnecessary.
        val user = api.withToken(account, afterLogin = { it.user ?: api.user(it.token) }, block = api::user)
        val premiumEnd = user.optLong("premium_end_time", 0) * 1000
        val premium = user.optBoolean("is_premium", false) ||
            premiumEnd > System.currentTimeMillis() ||
            user.optString("state_label").contains("Premium", ignoreCase = true)
        val traffic = user.optJSONObject("traffic")
        // "left"/"total" in bytes; null for accounts without a daily limit
        val trafficLeft = traffic?.takeUnless { it.isNull("left") }?.optLong("left", -1) ?: -1
        val trafficTotal = traffic?.takeUnless { it.isNull("total") }?.optLong("total", -1) ?: -1
        AccountInfo(
            valid = true,
            premium = premium,
            premiumUntil = premiumEnd,
            trafficLeft = trafficLeft,
            trafficTotal = trafficTotal,
            trafficUnlimited = premium && trafficLeft < 0 && trafficTotal < 0,
            statusText = if (premium) "Premium" else freeStatusText
        )
    }

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            // The API requires a session even for file/info
            if (account == null) {
                return@withContext LinkInfo(online = null, note = Texts.t("hoster_rapidgator_check_needs_account"))
            }
            val id = fileId(url)
            try {
                api.withToken(account) { token ->
                    val file = api.call("file/info", mapOf("file_id" to id, "token" to token)).optJSONObject("file")
                    if (file == null) LinkInfo(online = false, note = Texts.t("hoster_file_not_found"))
                    else LinkInfo(
                        online = true,
                        fileName = file.optString("name").ifBlank { null },
                        fileSize = file.optLong("size", -1)
                    )
                }
            } catch (e: RapidgatorApi.LoginFailed) {
                // An account problem is not "file offline"
                LinkInfo(online = null, note = e.message)
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
            api.withToken(account) { token ->
                val resp = api.call("file/download", mapOf("file_id" to id, "token" to token))
                val direct = DirectLinks.https(resp.optString("download_url"))
                if (direct.isBlank()) throw HosterException(Texts.t("hoster_rapidgator_no_download_url"), true)
                // Name, size and MD5 for the integrity check; optional, an error
                // here must not prevent the download.
                val info = runCatching {
                    api.call("file/info", mapOf("file_id" to id, "token" to token)).optJSONObject("file")
                }.getOrNull()
                ResolvedLink(
                    directUrl = direct,
                    fileName = info?.optString("name")?.ifBlank { null },
                    fileSize = info?.optLong("size", -1) ?: -1,
                    hash = info?.optString("hash")?.lowercase()?.takeIf { it.length == 32 }
                )
            }
        }
}
