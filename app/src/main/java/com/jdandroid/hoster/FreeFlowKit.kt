package com.jdandroid.hoster

import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** Direct link the captcha view intercepted, as HTTPS; null when there is none. */
internal fun FreeHints.browserLink(): String? =
    direktUrlAusBrowser?.takeIf { it.isNotBlank() }?.let { DirectLinks.https(it) }

/**
 * Waits until [readyAt] (wall clock). Remainders up to [MAX_INLINE_WAIT_MS]
 * run in-process plus [marginMs]; longer ones go to the engine as
 * [WaitException] with [text], so the job neither holds a slot nor keeps the
 * wake lock while the hoster counts down.
 */
internal suspend fun awaitCountdown(
    readyAt: Long,
    text: String,
    marginMs: Long = 500,
    now: () -> Long = System::currentTimeMillis
) {
    val remaining = readyAt - now()
    if (remaining > MAX_INLINE_WAIT_MS) throw WaitException(((remaining + 999) / 1000).toInt() + 1, text)
    if (remaining > 0) delay(remaining + marginMs)
}

internal const val MAX_INLINE_WAIT_MS = 5_000L

/** Where a [walkToFile] ended: the direct link, if any, and the last response for the error checks. */
internal class RedirectWalk(val direct: String?, val last: PageResponse)

/**
 * Follows the redirects after the free form without loading the file. A
 * Location that [isDirect] accepts, or a hop that answers with the file
 * itself, is the direct link; a page ends the walk with the link [linkIn]
 * finds in it. A file in [first] (the form response) has no address of its
 * own, so it yields no link. [onPage] sees every fetched hop (block checks).
 */
internal fun walkToFile(
    first: PageResponse,
    firstUrl: String,
    maxHops: Int,
    isDirect: (String) -> Boolean,
    fetch: (url: String, referer: String) -> PageResponse,
    linkIn: (String) -> String?,
    onPage: (PageResponse) -> Unit = {}
): RedirectWalk {
    var resp = first
    var currentUrl = firstUrl
    repeat(maxHops) {
        val location = resp.location
        if (resp.code !in 300..399 || location.isNullOrBlank()) {
            return RedirectWalk(if (resp.isFile) null else linkIn(resp.body), resp)
        }
        val target = resolveLocation(currentUrl, location)
        if (isDirect(target)) return RedirectWalk(target, resp)
        resp = fetch(target, currentUrl)
        currentUrl = target
        onPage(resp)
        if (resp.code in 200..299 && resp.isFile) return RedirectWalk(target, resp)
    }
    return RedirectWalk(null, resp)
}

/** Resolves a Location header (possibly relative) against the address it came from. */
internal fun resolveLocation(base: String, location: String): String =
    base.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location

/**
 * Headers of the file request in free mode: browser user agent (cf_clearance
 * is bound to it), [referer] as in the browser and the session cookies, those
 * only for the hoster's own [siteHosts], never for a foreign CDN [directUrl].
 */
internal fun freeFileHeaders(
    referer: String,
    directUrl: String,
    cookies: String?,
    siteHosts: Set<String>
): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers["User-Agent"] = Http.browserUa
    headers["Referer"] = referer
    if (DirectLinks.isSiteHost(directUrl, siteHosts)) {
        cookies?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
    }
    return headers
}

/**
 * Free flow state per file id, in-process only. An entry [expired] reports
 * as stale reads as absent, so the flow starts over.
 */
internal class FreeSessions<S : Any>(private val expired: (S) -> Boolean) {
    private val sessions = ConcurrentHashMap<String, S>()

    operator fun get(id: String): S? = sessions[id]?.takeUnless(expired)

    operator fun set(id: String, session: S) {
        sessions[id] = session
    }

    /** Removes the entry, stale or not: its name and size still serve an adopted browser link. */
    fun remove(id: String): S? = sessions.remove(id)
}

/**
 * Browser-like session of one free flow: its own cookie store and a client
 * using it, so parallel free downloads never mix their server sessions.
 */
internal open class FreeBrowserSession(baseClient: OkHttpClient, private val maxAgeMs: Long) {
    val jar = MemoryCookieJar()
    val client: OkHttpClient = baseClient.newBuilder().followRedirects(true).cookieJar(jar).build()
    private val createdAt = System.currentTimeMillis()

    val expired: Boolean get() = System.currentTimeMillis() - createdAt > maxAgeMs

    fun cookieHeader(url: String): String? = jar.cookieHeader(url)

    fun cookiesForBrowser(): List<String> = jar.cookiesForBrowser()
}
