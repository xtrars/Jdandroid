package com.jdandroid

import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.FreeSessions
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.PageResponse
import com.jdandroid.hoster.WaitException
import com.jdandroid.hoster.awaitCountdown
import com.jdandroid.hoster.browserLink
import com.jdandroid.hoster.freeFileHeaders
import com.jdandroid.hoster.walkToFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Shared pieces of the website free flows: browser link, countdown, redirect walk, headers, state. */
class FreeFlowKitTest {

    private fun page(body: String = "", code: Int = 200) = PageResponse(code, body, contentType = "text/html")
    private fun redirect(location: String) = PageResponse(302, "", location = location, contentType = "text/html")
    private fun file() = PageResponse(200, "", contentType = "application/octet-stream")

    private val isDirect: (String) -> Boolean = { it.contains("://files.") }
    private val linkIn: (String) -> String? = { body -> Regex("""https://files\.[^"'\s<>]+""").find(body)?.value }

    @Test
    fun `Browserlink wird als HTTPS uebernommen, leer zaehlt nicht`() {
        assertEquals("https://files.host/x", FreeHints(direktUrlAusBrowser = "http://files.host/x").browserLink())
        assertNull(FreeHints(direktUrlAusBrowser = " ").browserLink())
        assertNull(FreeHints().browserLink())
    }

    @Test
    fun `langer Countdown geht als Wartezeit an die Engine`() = runBlocking {
        try {
            awaitCountdown(readyAt = 30_000, text = "warten", now = { 0L })
            fail("WaitException erwartet")
        } catch (e: WaitException) {
            assertEquals(31, e.seconds)
        }
    }

    @Test
    fun `kurzer Countdown wartet im Prozess mit Zuschlag`() = runBlocking {
        val start = System.nanoTime()
        awaitCountdown(readyAt = 100, text = "warten", marginMs = 50, now = { 0L })
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 150)
        awaitCountdown(readyAt = 0, text = "warten", now = { 10L })
    }

    @Test
    fun `Weiterleitung auf den Dateiserver ist der Direktlink ohne Abruf`() {
        val walk = walkToFile(
            redirect("https://files.host/a"), "https://host/form", 4, isDirect,
            fetch = { _, _ -> fail("kein Abruf erwartet"); page() }, linkIn = linkIn
        )
        assertEquals("https://files.host/a", walk.direct)
    }

    @Test
    fun `relative Weiterleitung, deren Ziel die Datei liefert, ist der Direktlink`() {
        val fetched = ArrayList<Pair<String, String>>()
        val checked = ArrayList<PageResponse>()
        val walk = walkToFile(
            redirect("/dl/token"), "https://host/form", 4, isDirect,
            fetch = { url, referer -> fetched += url to referer; file() },
            linkIn = linkIn,
            onPage = { checked += it }
        )
        assertEquals("https://host/dl/token", walk.direct)
        assertEquals(listOf("https://host/dl/token" to "https://host/form"), fetched)
        assertEquals(1, checked.size)
    }

    @Test
    fun `Seite am Ende liefert den Link aus dem Text`() {
        val walk = walkToFile(
            redirect("https://host/next"), "https://host/form", 4, isDirect,
            fetch = { _, _ -> page("""<a href="https://files.host/b">Download</a>""") }, linkIn = linkIn
        )
        assertEquals("https://files.host/b", walk.direct)
    }

    @Test
    fun `Datei als Formularantwort hat keine eigene Adresse`() {
        val walk = walkToFile(file(), "https://host/form", 4, isDirect, fetch = { _, _ -> page() }, linkIn = { "x" })
        assertNull(walk.direct)
    }

    @Test
    fun `Weiterleitungskette endet nach der Hoechstzahl`() {
        var hops = 0
        val walk = walkToFile(
            redirect("https://host/1"), "https://host/form", 3, isDirect,
            fetch = { _, _ -> hops++; redirect("https://host/${hops + 1}") }, linkIn = linkIn
        )
        assertNull(walk.direct)
        assertEquals(3, hops)
        assertEquals(302, walk.last.code)
    }

    @Test
    fun `Cookies nur fuer die eigenen Hosts`() {
        val hosts = setOf("host.com", "www.host.com")
        val own = freeFileHeaders("https://host.com/f", "https://www.host.com/dl/x.rar", " a=1 ", hosts)
        assertEquals(mapOf("User-Agent" to Http.browserUa, "Referer" to "https://host.com/f", "Cookie" to "a=1"), own)
        val cdn = freeFileHeaders("https://host.com/f", "https://cdn.other.net/x.rar", "a=1", hosts)
        assertNull(cdn["Cookie"])
    }

    @Test
    fun `abgelaufener Stand gilt als nicht vorhanden, bleibt aber fuer den Browserlink`() {
        val sessions = FreeSessions<String> { it.startsWith("alt") }
        sessions["a"] = "alt-name"
        sessions["b"] = "neu"
        assertNull(sessions["a"])
        assertEquals("neu", sessions["b"])
        assertEquals("alt-name", sessions.remove("a"))
        assertNull(sessions.remove("a"))
    }
}
