package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

enum class AccountType { USERNAME_PASSWORD, API_KEY }

data class ResolvedLink(
    val directUrl: String,
    val fileName: String? = null,
    val fileSize: Long = -1,
    /** Hex checksum (MD5 = 32, SHA-1 = 40 chars) when the hoster provides one. */
    val hash: String? = null,
    /** Extra headers for the file request (e.g. cookies in free mode). */
    val headers: Map<String, String> = emptyMap()
)

/**
 * Result of the link collector's online check. [online] null = not checkable
 * (e.g. account required); [note] explains that to the user.
 */
data class LinkInfo(
    val online: Boolean?,
    val fileName: String? = null,
    val fileSize: Long = -1,
    val note: String? = null
)

data class AccountInfo(
    val valid: Boolean,
    val premiumUntil: Long = 0,
    /** Remaining traffic in bytes, -1 = unknown. */
    val trafficLeft: Long = -1,
    /** Total quota in bytes (for the progress bar), -1 = unknown. */
    val trafficTotal: Long = -1,
    /** Hoster without a traffic limit (e.g. 1fichier Premium). */
    val trafficUnlimited: Boolean = false,
    val statusText: String
)

/**
 * [permanent] = true: retrying without user action is pointless
 * (file offline, no premium, invalid account).
 */
open class HosterException(message: String, val permanent: Boolean = false) : Exception(message)

/** File no longer exists at the hoster; permanent, detectable without text matching. */
class FileOfflineException : HosterException(Texts.t("hoster_file_offline"), permanent = true)

/**
 * Free mode: the hoster demands a wait of [seconds] before the download may be
 * (re)tried. Not permanent; the engine re-queues the entry with a matching
 * retryAt without counting the attempt.
 */
class WaitException(val seconds: Int, message: String) : HosterException(message, permanent = false)

/**
 * Free mode: a captcha is required. [pageUrl] is the page where the user solves
 * it in the embedded browser; the intercepted direct link comes back as
 * [FreeHints.direktUrlAusBrowser].
 *
 * A hoster that does the groundwork itself (timer, unlock) and leaves only the
 * captcha to the browser passes its session cookies along: [cookies] in
 * Set-Cookie format ("name=value; domain=…; path=/; secure"), [cookieUrl] the
 * address they apply to. The captcha view sets them when it opens, so they
 * survive other views clearing the browser cookies and two waiting entries do
 * not overwrite each other.
 */
class CaptchaRequiredException(
    val pageUrl: String,
    message: String,
    val cookieUrl: String? = null,
    val cookies: List<String> = emptyList()
) : HosterException(message, permanent = false)

/**
 * Hints for [Hoster.resolveFree]: a direct link already intercepted in the
 * browser (captcha view) plus the browser cookies of the hoster domain
 * ("Name=Value; ..."). Both null without a browser round trip.
 */
data class FreeHints(
    val direktUrlAusBrowser: String? = null,
    val cookies: String? = null
)

/**
 * Durations in hoster messages ("in 5 min"). Texts.t has no plurals, so unit
 * abbreviations are used: whole hours as h, whole minutes as min, else seconds.
 */
object HosterDurations {
    fun text(seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> Texts.t("hoster_duration_hours", seconds / 3600)
        seconds >= 60 && seconds % 60 == 0 -> Texts.t("hoster_duration_minutes", seconds / 60)
        else -> Texts.t("hoster_duration_seconds", seconds)
    }
}

/**
 * Default for [Hoster.isDirectDownloadUrl]: file server addresses live on a
 * host other than the main domain and end with a file extension. Page links,
 * scripts/images and CGI calls (tracker.cgi) are excluded.
 */
object DirectLinks {
    val assetExtensions = setOf(
        "html", "htm", "php", "css", "js", "png", "jpg", "jpeg", "gif",
        "svg", "ico", "woff", "woff2", "ttf", "webp", "json", "xml", "cgi"
    )

    fun isDirectDownloadUrl(url: String, siteHosts: Set<String>): Boolean {
        val http = url.toHttpUrlOrNull() ?: return false
        if (http.host.lowercase() in siteHosts) return false
        val last = http.pathSegments.lastOrNull().orEmpty()
        val ext = last.substringAfterLast('.', "").lowercase()
        return last.contains('.') && ext.isNotEmpty() && ext !in assetExtensions
    }

    /** True when the URL's host is one of [siteHosts] or a subdomain of one (file servers). */
    fun isSiteHost(url: String, siteHosts: Set<String>): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return siteHosts.any { host == it || host.endsWith(".$it") }
    }
}

interface Hoster {
    val id: String
    val displayName: String
    val accountType: AccountType

    /** Hint for the account dialog, e.g. where to find the API key. */
    val accountHint: String

    /**
     * Login page for signing in via the embedded browser. Non-null when the
     * hoster requires a captcha and cannot be logged in headlessly; the session
     * cookies are then taken over from the browser.
     */
    val webLoginUrl: String? get() = null

    /**
     * Main domain(s) of the website in lower case (with www variants). Basis
     * for [isDirectDownloadUrl] and the host filter of the captcha view.
     */
    val siteHosts: Set<String>

    /**
     * True once [resolveFree] is implemented. Only affects the account status
     * ([freeStatusText]); the engine calls [resolveFree] regardless.
     */
    val supportsFree: Boolean get() = false

    /** Account status of an account without premium. */
    val freeStatusText: String
        get() = if (supportsFree) Texts.t("hoster_free_status") else Texts.t("hoster_free_status_unsupported")

    fun matches(url: String): Boolean

    /** Validates the credentials and returns the premium status. */
    suspend fun checkAccount(account: Account): AccountInfo

    /** Resolves a hoster link into a direct download URL using a premium account. */
    suspend fun resolve(url: String, account: Account?): ResolvedLink

    /**
     * Free mode (no account): direct link without premium. May throw
     * [WaitException] (wait time) and [CaptchaRequiredException] (captcha in
     * the browser); with [FreeHints.direktUrlAusBrowser] the link already
     * exists and only needs to be adopted (possibly with [FreeHints.cookies]
     * as [ResolvedLink.headers]). Default: unsupported (permanent).
     */
    suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink =
        throw HosterException(Texts.t("hoster_free_unsupported"), true)

    /**
     * Detects navigation to the file server in the captcha view: that address
     * is intercepted and handed to the engine as the direct link.
     */
    fun isDirectDownloadUrl(url: String): Boolean = DirectLinks.isDirectDownloadUrl(url, siteHosts)

    /**
     * Checks without downloading whether the file is online and returns name
     * and size when possible (link collector). Default: not checkable.
     */
    suspend fun checkLink(url: String, account: Account?): LinkInfo =
        LinkInfo(online = null, note = Texts.t("hoster_no_check_possible"))
}

object Http {
    /**
     * User agent of the system WebView, determined at app start. Cloudflare
     * binds the cf_clearance cookie to the user agent it was issued for, so a
     * session taken over from the browser login is only valid with the same one.
     */
    @Volatile
    var browserUserAgent: String? = null

    /** Browser user agent for hoster pages: XFileSharing/Cloudflare reject bot user agents. */
    val browserUa: String
        get() = browserUserAgent
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor(CrossSiteRedirectInterceptor)
        .build()

    /** Same host or same parent domain (s12.ddownload.com and ddownload.com). */
    fun sameSite(a: HttpUrl, b: HttpUrl): Boolean {
        val ha = a.host.lowercase()
        val hb = b.host.lowercase()
        if (ha == hb) return true
        val domain = parentDomain(ha) ?: return false
        return domain == parentDomain(hb)
    }

    /** Last two labels of a host name; none for IP addresses and single labels. */
    private fun parentDomain(host: String): String? {
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        return labels.takeLast(2).joinToString(".")
    }

    // No version number: it kept drifting from versionName in build.gradle.kts
    const val USER_AGENT = "Mozilla/5.0 (Android) JDAndroid"

    /**
     * Upper bound for responses read as text (API responses are small). Always
     * read with peekBody(MAX_TEXT_BYTES): if a server unexpectedly answers with
     * a file instead of JSON, an unbounded string() would load the whole
     * content into memory (OutOfMemoryError).
     */
    const val MAX_TEXT_BYTES = 2L * 1024 * 1024
}

/**
 * OkHttp copies Cookie and Referer of the original request onto every
 * redirect hop (only Authorization is dropped on a host change); a file
 * server redirecting to a foreign CDN would receive the hoster's session.
 */
internal object CrossSiteRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (Http.sameSite(chain.call().request().url, request.url)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().removeHeader("Cookie").removeHeader("Referer").build())
    }
}

object HosterRegistry {
    val hosters: List<Hoster> = listOf(
        RapidgatorHoster(),
        OneFichierHoster(),
        DdownloadHoster()
    )

    fun byId(id: String): Hoster? = hosters.find { it.id == id }

    fun forUrl(url: String): Hoster? = hosters.find { it.matches(url) }
}

object LinkParser {
    /** Quotes, angle and square brackets terminate a URL. */
    private val urlRegex = Regex("""https?://[^\s"'<>\[\]]+""")

    /** Extracts all supported links from arbitrary text. */
    fun parse(text: String): List<Pair<String, Hoster>> =
        urlRegex.findAll(text)
            // Commas and semicolons separate links ("url1,url2"); drop parts without http
            .flatMap { m -> m.value.split(',', ';').asSequence().filter { it.startsWith("http") } }
            .map { it.trimEnd(')', ']', '>', '.', ',', ';', '"', '\'') }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { url -> HosterRegistry.forUrl(url)?.let { url to it } }
            .toList()
}
