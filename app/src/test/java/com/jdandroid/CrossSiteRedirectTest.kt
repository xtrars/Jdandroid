package com.jdandroid

import com.jdandroid.hoster.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cookie and Referer survive redirects only within the hoster domain. */
class CrossSiteRedirectTest {

    @Test
    fun gleicheSeiteUmfasstSubdomains() {
        assertTrue(Http.sameSite("https://ddownload.com/x".toHttpUrl(), "https://s12.ddownload.com/d/x.rar".toHttpUrl()))
        assertTrue(Http.sameSite("https://a-3.1fichier.com/x".toHttpUrl(), "https://a-7.1fichier.com/y".toHttpUrl()))
        assertTrue(Http.sameSite("https://pr5.rapidgator.net/x".toHttpUrl(), "https://PR6.rapidgator.net/x".toHttpUrl()))
        assertFalse(Http.sameSite("https://ddownload.com/x".toHttpUrl(), "https://cdn.example.net/x.rar".toHttpUrl()))
        assertFalse(Http.sameSite("https://ddownload.com/x".toHttpUrl(), "https://ddownload.com.evil.org/x".toHttpUrl()))
        assertFalse(Http.sameSite("http://localhost:1/x".toHttpUrl(), "http://127.0.0.1:1/x".toHttpUrl()))
    }

    @Test
    fun weiterleitungAufFremdenHostVerliertCookieUndReferer() {
        MockWebServer().use { server ->
            server.start()
            val port = server.port
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:$port/file.rar"))
            server.enqueue(MockResponse().setBody("data"))
            val request = Request.Builder()
                .url("http://localhost:$port/start")
                .header("Cookie", "sdata__=abc")
                .header("Referer", "http://localhost:$port/page")
                .header("User-Agent", "Test")
                .build()
            Http.client.newCall(request).execute().use { assertEquals("data", it.body.string()) }
            val first = server.takeRequest()
            assertEquals("sdata__=abc", first.getHeader("Cookie"))
            val second = server.takeRequest()
            assertEquals("/file.rar", second.path)
            assertNull(second.getHeader("Cookie"))
            assertNull(second.getHeader("Referer"))
            assertEquals("Test", second.getHeader("User-Agent"))
        }
    }

    @Test
    fun weiterleitungAufDemselbenHostBehaeltCookie() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/file.rar"))
            server.enqueue(MockResponse().setBody("data"))
            val request = Request.Builder()
                .url(server.url("/start"))
                .header("Cookie", "sdata__=abc")
                .header("Referer", server.url("/page").toString())
                .build()
            Http.client.newCall(request).execute().use { assertEquals("data", it.body.string()) }
            server.takeRequest()
            val second = server.takeRequest()
            assertEquals("sdata__=abc", second.getHeader("Cookie"))
            assertEquals(server.url("/page").toString(), second.getHeader("Referer"))
        }
    }
}
