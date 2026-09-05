package com.jdandroid

import com.jdandroid.hoster.Http
import com.jdandroid.hoster.MemoryCookieJar
import com.jdandroid.hoster.fetchPage
import com.jdandroid.hoster.isTextualContent
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared page fetch and cookie store of the hosters: a file (attachment or
 * non-text) is never read into memory, cookies are replaced per name, domain
 * and path.
 */
class HttpSessionTest {

    private fun cookie(name: String, value: String, path: String = "/", expiresAt: Long = Long.MAX_VALUE): Cookie =
        Cookie.Builder().name(name).value(value).domain("example.com").path(path).expiresAt(expiresAt).build()

    @Test
    fun textantwortenWerdenGelesenDateienNicht() {
        assertTrue(isTextualContent("text/html; charset=UTF-8"))
        assertTrue(isTextualContent("application/json"))
        assertTrue(isTextualContent("application/xml"))
        assertTrue(isTextualContent(null))
        assertTrue(isTextualContent(""))
        assertFalse(isTextualContent("application/octet-stream"))
        assertFalse(isTextualContent("video/x-matroska"))
        assertFalse(isTextualContent("application/x-rar-compressed"))
        assertFalse(isTextualContent("application/zip"))
        // An attachment is a file even when it is labelled as text
        assertFalse(isTextualContent("text/html", "attachment; filename=\"a.rar\""))
        assertFalse(isTextualContent(null, "attachment"))
        assertTrue(isTextualContent("text/html", "inline"))
    }

    @Test
    fun dateiantwortLaesstDenTextLeer() = MockWebServer().use { server ->
        server.start()
        server.enqueue(
            MockResponse().setBody("RAR!").addHeader("Content-Type", "text/html")
                .addHeader("Content-Disposition", "attachment; filename=\"a.rar\"")
        )
        server.enqueue(MockResponse().setBody("RAR!").addHeader("Content-Type", "application/octet-stream"))
        server.enqueue(MockResponse().setBody("<html>x</html>").addHeader("Content-Type", "text/html"))
        val base = server.url("/").toString().trimEnd('/')
        val attachment = Http.client.fetchPage("$base/f")
        assertTrue(attachment.isFile)
        assertEquals("", attachment.body)
        assertEquals("attachment; filename=\"a.rar\"", attachment.contentDisposition)
        val binary = Http.client.fetchPage("$base/f")
        assertTrue(binary.isFile)
        assertEquals("", binary.body)
        val page = Http.client.fetchPage("$base/f")
        assertFalse(page.isFile)
        assertEquals("<html>x</html>", page.body)
        assertEquals("$base/f", page.finalUrl)
    }

    @Test
    fun formularUndAjaxSetzenDieBrowserKopfzeilen() = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next"))
        server.enqueue(MockResponse().setBody("{}").addHeader("Content-Type", "application/json"))
        val base = server.url("/").toString().trimEnd('/')
        val posted = Http.client.fetchPage(
            "$base/form", referer = "$base/page", form = mapOf("op" to "download2", "id" to "a&b"),
            acceptLanguage = "de,en;q=0.8", followRedirects = false
        )
        assertEquals(302, posted.code)
        assertEquals("/next", posted.location)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("op=download2&id=a%26b", request.body.readUtf8())
        assertEquals("$base/page", request.getHeader("Referer"))
        assertEquals("de,en;q=0.8", request.getHeader("Accept-Language"))
        assertEquals(Http.browserUa, request.getHeader("User-Agent"))
        assertTrue(request.getHeader("Accept")!!.startsWith("text/html"))
        assertNull(request.getHeader("X-Requested-With"))

        val ajax = Http.client.fetchPage("$base/ajax", ajax = true)
        assertEquals("{}", ajax.body)
        val ajaxRequest = server.takeRequest()
        assertEquals("GET", ajaxRequest.method)
        assertEquals("XMLHttpRequest", ajaxRequest.getHeader("X-Requested-With"))
        assertTrue(ajaxRequest.getHeader("Accept")!!.startsWith("application/json"))
    }

    @Test
    fun cookiesWerdenJeNameDomainUndPfadErsetzt() {
        val jar = MemoryCookieJar()
        val url = "https://example.com/download/x".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("sid", "1"), cookie("sid", "2", path = "/download")))
        jar.saveFromResponse(url, listOf(cookie("sid", "3")))
        jar.add(cookie("old", "x", expiresAt = 1L))
        assertEquals(setOf("sid=3", "sid=2"), jar.loadForRequest(url).map { "${it.name}=${it.value}" }.toSet())
        assertEquals(setOf("sid=3", "sid=2"), jar.cookieHeader("https://example.com/download/x")!!.split("; ").toSet())
        assertEquals("sid=3", jar.cookieHeader("https://example.com/"))
        assertNull(jar.cookieHeader("https://other.org/"))
        assertNull(jar.cookieHeader("not a url"))
        assertEquals(2, jar.cookiesForBrowser().size)
        assertTrue(jar.cookiesForBrowser().all { it.startsWith("sid=") })
    }

    @Test
    fun seedErsetztNurEinenLeerenStoreOderMitForce() {
        val jar = MemoryCookieJar()
        jar.seed(listOf(cookie("xfss", "browser")))
        jar.add(cookie("xfss", "server"))
        jar.seed(listOf(cookie("xfss", "browser")))
        assertEquals("xfss=server", jar.cookieHeader("https://example.com/"))
        jar.seed(listOf(cookie("xfss", "browser")), force = true)
        assertEquals("xfss=browser", jar.cookieHeader("https://example.com/"))
    }
}
