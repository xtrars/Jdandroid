package com.jdandroid.hoster

import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.Texts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pages of ddownload.com (XFileSharing) shared by premium and free flow:
 * file code, file name and size, the download form, direct links and blocks.
 * Pure parsing without network access; the free page's own notices live in
 * [DdownloadFreePage], the account page in [DdownloadAccountPage].
 */
internal object DdownloadPage {

    /** Wait after a Cloudflare block or an unreachable account page, as at 1fichier. */
    const val BLOCK_WAIT_SECONDS = 15 * 60 + 1
    const val MAINTENANCE_WAIT_SECONDS = 20 * 60 + 1

    /** Main domains that never serve a file (pages only). */
    val SITE_HOSTS = setOf("ddownload.com", "www.ddownload.com", "ddl.to", "www.ddl.to")

    /** Subdomains serving pages, never files. */
    private val SERVICE_HOSTS = setOf("my.ddownload.com", "my.ddl.to")

    /**
     * File codes are exactly 12 chars [a-z0-9]; the lookahead prevents longer
     * paths (e.g. /register.html) from being read as a code.
     */
    private val linkPattern =
        Regex("""https?://(?:www\.)?(?:ddownload\.com|ddl\.to)/(?:f/|d/)?([a-z0-9]{12})(?![A-Za-z0-9])""")

    /**
     * Direct links live on file servers (subdomain other than www), never on
     * the main domain and never under /cgi-bin/ (tracker.cgi lives there).
     */
    private val fileServerRegex =
        Regex("""https?://(?!www\.)[a-z0-9-]+\.(?:ddownload\.com|ddl\.to)(?::\d+)?/[^\s"'<>]+""")

    fun matches(url: String): Boolean = linkPattern.containsMatchIn(url)

    fun fileCode(url: String): String =
        linkPattern.find(url)?.groupValues?.get(1)
            ?: throw HosterException(Texts.t("hoster_ddownload_invalid_link"), true)

    /**
     * Detects maintenance and Cloudflare/WAF blocks so the message does not
     * read "wrong password". Both are waited out like at the other hosters;
     * a few quick retries would only fail the queue after minutes.
     */
    fun checkBlocked(resp: PageResponse) {
        if (resp.code == 503) throw WaitException(MAINTENANCE_WAIT_SECONDS, Texts.t("hoster_ddownload_maintenance"))
        val blocked = resp.code == 403 ||
            resp.body.contains("Just a moment", true) ||
            resp.body.contains("cf-browser-verification", true) ||
            resp.body.contains("Attention Required", true) ||
            resp.body.contains("Enable JavaScript and cookies", true)
        if (blocked) {
            throw WaitException(BLOCK_WAIT_SECONDS, Texts.t("hoster_ddownload_cloudflare_blocked", resp.code))
        }
    }

    /**
     * Whether the account page was served to a logged-in session. Logged out,
     * ddownload redirects every account address to the login form. Page
     * markers are no evidence: the current layout (/account) has none of the
     * old XFileSharing ones ("op=logout", "Account type").
     */
    fun isLoggedIn(page: PageResponse): Boolean =
        !isLoginPage(page.finalUrl) && !hasLoginForm(page.body)

    private fun isLoginPage(url: String): Boolean {
        val http = url.toHttpUrlOrNull() ?: return false
        return http.encodedPath.startsWith("/login") || http.queryParameter("op") == "login"
    }

    private fun hasLoginForm(html: String): Boolean =
        Regex("""<input\b[^>]*\bname=["']password["']""", RegexOption.IGNORE_CASE).containsMatchIn(html) &&
            Regex("""<input\b[^>]*\bname=["']login["']""", RegexOption.IGNORE_CASE).containsMatchIn(html)

    fun isDeleted(html: String): Boolean =
        html.contains("File Not Found", true) ||
            html.contains("file was deleted", true) ||
            html.contains("No such file", true)

    /**
     * File name from an XFileSharing file page. Order: the heading with class
     * dk-dl-name (current layout), then the fname field, finally the title,
     * where ddownload replaces dots with spaces ("Download scn smps8 S37E02
     * rar"), so it only counts if it still carries an extension.
     */
    fun fileName(html: String): String? {
        Regex(
            """<h[12]\b[^>]*class=["'][^"']*\bdk-dl-name\b[^"']*["'][^>]*>\s*([^<]+?)\s*</h[12]>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.removePrefix("Download ")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return ArchiveNames.repairName(it) }
        Regex("""name=["']fname["']\s+value=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("""value=(["'])(.*?)\1\s+name=["']fname["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        // The site suffix is separated by " - " or " | "; a hyphen inside the
        // name ("scn-smps8-S37E02 rar") is part of it
        return Regex("""<title>([^<]+?)(?:\s+[-|–]\s+.*)?</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.removePrefix("Download ")?.trim()
            ?.let { ArchiveNames.repairName(it) }
            ?.takeIf {
                it.isNotBlank() && !it.contains("ddownload", true) &&
                    Regex("""\.[A-Za-z0-9]{1,10}$""").containsMatchIn(it)
            }
    }

    /** Size such as "1.2 GB" from the file page. */
    fun fileSize(html: String): Long {
        // Visible text only, starting at the file name: ads and quota figures
        // ("200 GB traffic per day") come before it
        val text = DdownloadAccountPage.visibleText(html)
        val start = fileName(html)?.let { text.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(KB|MB|GB|TB)\b""", RegexOption.IGNORE_CASE).find(text, start)
            ?: return -1
        return DdownloadAccountPage.toBytes(m.groupValues[1], m.groupValues[2])
    }

    /** Collects all hidden fields of a form (attribute order does not matter). */
    fun hiddenInputs(html: String): Map<String, String> {
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

    /** The form with the download operation, so no foreign fields are sent along. */
    fun formBlock(html: String): String? =
        Regex("""<form\b[^>]*>.*?</form>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .firstOrNull { it.value.contains("op\"", true) && it.value.contains("download", true) }
            ?.value

    /**
     * Fields of the premium download form. The XFileSharing field names are
     * known, so the form is built by hand if needed: for logged-in users the
     * page sometimes lacks a complete form, and resolving must not fail on that.
     */
    fun downloadForm(html: String, code: String): Map<String, String> {
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

    /**
     * True for addresses with a file name on a host other than the main
     * domain: file servers (subdomain, also with a port like :183, also under
     * /cgi-bin/dl.cgi/) and foreign CDN hosts the form response may redirect
     * to. Page links, tracker.cgi, relative redirects and /login.html fail the
     * host and extension checks.
     */
    fun isFileServerUrl(url: String): Boolean {
        if (DirectLinks.isDirectDownloadUrl(url, SITE_HOSTS)) return true
        // File server subdomain with a download path, even without an extension
        // (e.g. /cgi-bin/dl.cgi/<token>): this is how the free form answers
        val http = url.toHttpUrlOrNull() ?: return false
        val host = http.host.lowercase()
        if (host in SITE_HOSTS || host in SERVICE_HOSTS) return false
        if (!Regex("""^[a-z0-9-]+\.(?:ddownload\.com|ddl\.to)$""").matches(host)) return false
        return Regex("""^/(?:cgi-bin/dl\.cgi|d|files)/[^/]+""").containsMatchIn(http.encodedPath) &&
            http.pathSegments.lastOrNull().orEmpty().isNotEmpty()
    }

    /**
     * Direct link from the HTML. Only file server addresses count: matching on
     * "contains download" would hit every address of the site itself
     * ("ddownload.com"), and tracker.cgi on the main domain is not a file.
     */
    fun extractDirectLink(html: String): String? =
        fileServerRegex.findAll(html)
            .map { it.value.trimEnd('\\', '"', '\'', ')').replace("&amp;", "&") }
            .firstOrNull { isFileServerUrl(it) }

    /**
     * No direct link: permanent only without a limit hint and without a server
     * error. 5xx and 429 (throttling) are temporary; [checkBlocked] only
     * catches 403/503.
     */
    fun resolveFailurePermanent(code: Int, limitReached: Boolean): Boolean =
        !limitReached && code !in 500..599 && code != 429
}
