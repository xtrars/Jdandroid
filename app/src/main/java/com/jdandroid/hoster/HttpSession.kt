package com.jdandroid.hoster

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-memory cookie store for one hoster session. OkHttp calls the jar from
 * several threads (parallel downloads of the same session), so all access is
 * synchronized. Cookies live only in-process.
 */
internal class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) { cookies.forEach { put(it) } }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
        val now = System.currentTimeMillis()
        store.filter { it.matches(url) && it.expiresAt > now }
    }

    /** Adds [cookie], replacing one with the same name, domain and path. */
    fun add(cookie: Cookie) {
        synchronized(store) { put(cookie) }
    }

    /**
     * Replaces the whole store with [cookies]; without [force] only when the
     * store is still empty, so a live server session is not thrown away.
     */
    fun seed(cookies: List<Cookie>, force: Boolean = false) {
        synchronized(store) {
            if (store.isNotEmpty() && !force) return
            store.clear()
            cookies.forEach { put(it) }
        }
    }

    /** Cookie header for [url], null without matching cookies. */
    fun cookieHeader(url: String): String? {
        val http = url.toHttpUrlOrNull() ?: return null
        return loadForRequest(http).joinToString("; ") { "${it.name}=${it.value}" }.ifBlank { null }
    }

    /** All unexpired cookies in Set-Cookie format for the browser. */
    fun cookiesForBrowser(): List<String> = synchronized(store) {
        val now = System.currentTimeMillis()
        store.filter { it.expiresAt > now }.map { it.toString() }
    }

    private fun put(cookie: Cookie) {
        store.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        store.add(cookie)
    }
}

/**
 * A page response with the body read as text. The body is only read for
 * textual content; a file (attachment or non-text content type) leaves it
 * empty so that a followed download redirect can never exhaust the heap.
 */
internal data class PageResponse(
    val code: Int,
    val body: String,
    val location: String? = null,
    val contentType: String? = null,
    /** Address after all followed redirects. */
    val finalUrl: String = "",
    val contentDisposition: String? = null
) {
    /** Response is a file, not a page (regardless of the status code). */
    val isFile: Boolean
        get() = !isTextualContent(contentType, contentDisposition)
}

/**
 * Only textual responses (pages, JSON, scripts) may be read into memory; a
 * missing content type counts as text. An attachment is always a file, even
 * when it is labelled as text.
 */
internal fun isTextualContent(contentType: String?, contentDisposition: String? = null): Boolean {
    if (contentDisposition?.contains("attachment", true) == true) return false
    val type = contentType.orEmpty().lowercase()
    return type.isBlank() || type.startsWith("text/") || type.contains("html") || type.contains("json") ||
        type.contains("xml") || type.contains("javascript")
}

/**
 * Fetches a page like a browser (user agent, Accept headers, Referer), as a
 * GET or, with [form], as a POST with form fields. With [ajax] the request
 * announces itself as XMLHttpRequest expecting JSON. [followRedirects] false
 * returns the redirect itself so a Location can be inspected without loading
 * its target.
 */
internal fun OkHttpClient.fetchPage(
    url: String,
    referer: String? = null,
    form: Map<String, String>? = null,
    acceptLanguage: String = "en,de;q=0.8",
    ajax: Boolean = false,
    followRedirects: Boolean = true
): PageResponse {
    val builder = Request.Builder()
        .url(url)
        .header("User-Agent", Http.browserUa)
        .header("Accept-Language", acceptLanguage)
    if (ajax) {
        builder.header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
    } else {
        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    }
    referer?.let { builder.header("Referer", it) }
    form?.let { fields ->
        builder.post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
    }
    val client = if (followRedirects) this else {
        newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    return client.newCall(builder.build()).execute().use { resp ->
        val contentType = resp.header("Content-Type")
        val disposition = resp.header("Content-Disposition")
        val text = if (isTextualContent(contentType, disposition)) {
            runCatching { resp.peekBody(Http.MAX_TEXT_BYTES).string() }.getOrDefault("")
        } else {
            ""
        }
        PageResponse(resp.code, text, resp.header("Location"), contentType, resp.request.url.toString(), disposition)
    }
}
