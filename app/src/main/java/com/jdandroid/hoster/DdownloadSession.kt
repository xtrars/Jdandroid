package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.data.plainCookies
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Network side of ddownload.com: one cookie store and client per account
 * ([FREE] for downloads without one) and the browser session taken over from
 * the embedded login.
 */
internal class DdownloadSession(
    /** Website address; tests replace it with a local server. */
    val siteBase: String
) {
    /** Host of [siteBase]; domain of the session cookies taken over from the browser. */
    private val siteHost = siteBase.toHttpUrl().host

    private val cookieJars = ConcurrentHashMap<Long, MemoryCookieJar>()
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    private fun jarFor(accountId: Long): MemoryCookieJar = cookieJars.getOrPut(accountId) { MemoryCookieJar() }

    private fun clientFor(accountId: Long): OkHttpClient = clients.getOrPut(accountId) {
        // Own cookie jar and no cross-site interceptor, but the app-wide connections and threads
        OkHttpClient.Builder()
            .connectionPool(Http.client.connectionPool)
            .dispatcher(Http.client.dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(jarFor(accountId))
            .build()
    }

    /** Fetches a page with the cookies of [accountId], as GET or with [form] as POST. */
    fun fetch(
        accountId: Long,
        url: String,
        form: Map<String, String>? = null,
        referer: String? = null,
        followRedirects: Boolean = true
    ): PageResponse = clientFor(accountId)
        .fetchPage(url, referer, form, acceptLanguage = "de,en;q=0.8", followRedirects = followRedirects)

    /** Cookie header from the store of [accountId] for [url]; null without matching cookies. */
    fun cookieHeader(accountId: Long, url: String): String? = cookieJars[accountId]?.cookieHeader(url)

    /**
     * Returns the account page HTML based on the session cookies taken over
     * from the browser; a headless form login is impossible because of the
     * Turnstile captcha. Later [fetch] calls with the account's id use the
     * same session.
     */
    fun accountPage(account: Account): String {
        val raw = account.plainCookies
        if (raw.isNullOrBlank()) {
            throw HosterException(Texts.t("hoster_ddownload_no_login"), permanent = true)
        }
        seedCookies(account.id, raw)

        var page = fetchAccountPage(account.id)
        if (!DdownloadPage.isLoggedIn(page)) {
            // The cookie store may hold a session the server "deleted"
            // (Set-Cookie with an expiry in the past). Start over once with the
            // stored browser cookies before treating the session as expired.
            seedCookies(account.id, raw, force = true)
            page = fetchAccountPage(account.id)
        }
        if (!DdownloadPage.isLoggedIn(page)) {
            throw HosterException(Texts.t("hoster_ddownload_session_expired"), permanent = true)
        }
        return page.body
    }

    private fun fetchAccountPage(accountId: Long): PageResponse {
        val page = fetch(accountId, "$siteBase/?op=my_account", referer = siteBase)
        DdownloadPage.checkBlocked(page)
        if (page.code !in 200..299) {
            // Server error: temporary, no reason to disable the account
            throw WaitException(
                DdownloadPage.BLOCK_WAIT_SECONDS, Texts.t("hoster_ddownload_account_page_unreachable", page.code)
            )
        }
        return page
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

    companion object {
        /** Cookie store of downloads without an account. */
        const val FREE = 0L
    }
}
