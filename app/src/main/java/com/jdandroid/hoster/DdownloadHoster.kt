package com.jdandroid.hoster

import com.jdandroid.core.FileNames
import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * ddownload.com, an XFileSharing hoster. Login only in the embedded browser
 * (the login form carries a Cloudflare Turnstile), the session cookies are
 * taken over; premium direct download via the two-step download form. Free
 * downloads go through [DdownloadFree]: blocks and wait times are read from
 * the page, the Turnstile in the download form is only solvable in the
 * embedded browser. Pages are parsed by [DdownloadPage] and
 * [DdownloadAccountPage], cookies and clients live in [DdownloadSession].
 */
class DdownloadHoster internal constructor(
    /** Website address; tests replace it with a local server. */
    private val siteBase: String
) : Hoster {

    constructor() : this("https://ddownload.com")

    override val id = "ddownload"
    override val displayName = "ddownload"
    // The web login is protected by Cloudflare Turnstile and cannot be solved
    // headlessly: the user signs in in the embedded browser and the session
    // is taken over.
    override val accountType = AccountType.BROWSER_LOGIN
    override val accountHint: String
        get() = Texts.t("hoster_ddownload_account_hint")
    override val webLoginUrl = "https://ddownload.com/login.html"

    override val supportsFree = true

    override val siteHosts = DdownloadPage.SITE_HOSTS

    private val session = DdownloadSession(siteBase)

    /** Free flow (blocks, countdown, Turnstile), see [DdownloadFree]. */
    internal val free = DdownloadFree(session)

    override fun matches(url: String) = DdownloadPage.matches(url)

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val html = session.accountPage(account)
        val (expire, premium, tier) = DdownloadAccountPage.status(DdownloadAccountPage.visibleText(html))

        val traffic = DdownloadAccountPage.plausibleTraffic(DdownloadAccountPage.parseTraffic(html))
        val trafficTotal = when {
            traffic.unlimited -> -1L
            traffic.total > 0 -> traffic.total
            premium && traffic.left >= 0 -> maxOf(DdownloadAccountPage.DAILY_QUOTA, traffic.left)
            else -> -1L
        }

        AccountInfo(
            valid = true,
            premium = premium,
            premiumUntil = expire,
            trafficLeft = traffic.left,
            trafficTotal = trafficTotal,
            trafficUnlimited = traffic.unlimited,
            statusText = (if (premium) tier else freeStatusText).let { status ->
                if (traffic.left < 0 && !traffic.unlimited) Texts.t("hoster_status_quota_unreadable", status) else status
            }
        )
    }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            if (account == null) throw HosterException(Texts.t("hoster_ddownload_premium_required"), true)
            val code = DdownloadPage.fileCode(url)
            session.accountPage(account)
            val pageUrl = "$siteBase/$code"
            val fetch = { target: String, form: Map<String, String>?, referer: String ->
                session.fetch(account.id, target, form, referer, followRedirects = false)
                    .also(DdownloadPage::checkBlocked)
            }

            var page = session.fetch(account.id, pageUrl, referer = siteBase)
            DdownloadPage.checkBlocked(page)
            // Account with "Direct Downloads": the page redirects straight to
            // the file; the client followed it, the final address is the link
            if (page.isFile && page.finalUrl.toHttpUrlOrNull()?.host?.lowercase() !in siteHosts) {
                return@withContext ResolvedLink(page.finalUrl, FileNames.fromDisposition(page.contentDisposition))
            }
            checkOffline(page.body)
            // Remember the name from the file page: after the redirect chain
            // the page text is a redirect without content
            val pageName = DdownloadPage.fileName(page.body)

            var direct = DdownloadPage.extractDirectLink(page.body)
            var formsSent = 0
            var hops = 0
            var currentUrl = pageUrl
            while (direct == null && hops++ < 6) {
                if (page.code in 300..399 && !page.location.isNullOrBlank()) {
                    // Redirect: if it points to a file, that is the direct link;
                    // otherwise follow the next page (without loading the file).
                    // Relative targets resolve against the last fetched address.
                    val target = resolveLocation(currentUrl, page.location)
                    if (DdownloadPage.isFileServerUrl(target)) { direct = target; break }
                    page = fetch(target, null, currentUrl)
                    currentUrl = target
                    // Response is already the file (address without extension,
                    // e.g. dl.cgi/<token>): the body was not read
                    if (page.code in 200..299 && page.isFile) { direct = target; break }
                    direct = DdownloadPage.extractDirectLink(page.body)
                    continue
                }
                if (formsSent >= 2) break
                // Submit the download form (op=download2, method_premium)
                // without following redirects: XFileSharing answers with a
                // redirect whose Location is already the direct link.
                val form = DdownloadPage.downloadForm(page.body, code)
                formsSent++
                page = fetch(pageUrl, form, pageUrl)
                currentUrl = pageUrl
                if (page.code in 200..299 && page.isFile) {
                    // The file itself came back on the POST: its address fetched
                    // via GET is only the file page, so there is no usable link
                    throw HosterException(Texts.t("hoster_ddownload_no_direct_link", page.code), permanent = false)
                }
                direct = DdownloadPage.extractDirectLink(page.body)
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
                    permanent = !transientForm && DdownloadPage.resolveFailurePermanent(page.code, limitReached)
                )
            }
            val fileName = pageName ?: DdownloadPage.fileName(page.body)
                ?: direct.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }
            ResolvedLink(DirectLinks.https(direct), fileName)
        }

    override suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink = free.resolve(url, hints)

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            val code = DdownloadPage.fileCode(url)
            // The public file page needs no login, but the browser user agent:
            // otherwise Cloudflare only serves a challenge.
            val resp = session.fetch(DdownloadSession.FREE, "$siteBase/$code")
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
                fileName = DdownloadPage.fileName(html),
                fileSize = DdownloadPage.fileSize(html)
            )
        }

    private fun checkOffline(html: String) {
        if (DdownloadPage.isDeleted(html)) throw FileOfflineException()
    }

    override fun isDirectDownloadUrl(url: String): Boolean = DdownloadPage.isFileServerUrl(url)
}
