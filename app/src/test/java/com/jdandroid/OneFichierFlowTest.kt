package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.FileOfflineException
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.OneFichierHoster
import com.jdandroid.hoster.WaitException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1fichier against a local server: the public link check and the whole free
 * flow (file page, countdown, form, redirect to the file server, hotlink,
 * blocks), plus the HTTP-level classification of API replies as a table. The
 * API itself cannot run here: org.json is only a stub on the JVM, the KO
 * messages are covered by [OneFichierFailureTest].
 */
class OneFichierFlowTest {

    private val id = "abcde12345"
    private val fileUrl = "https://1fichier.com/?$id"

    private fun html(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "text/html; charset=utf-8")

    private fun <T> withServer(block: (server: MockWebServer, base: String, hoster: OneFichierHoster) -> T): T =
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            block(server, base, OneFichierHoster("$base/v1", base, "$base/check_links.pl", Http.client))
        }

    /** File page with the download form; [count] is the countdown. */
    private fun dateiseite(base: String, count: Int = 0, form: Boolean = true) = """
        <html><head><title>Download archiv.part1.rar</title>
        <script>var count = $count;</script></head><body>
        <table class="premium">
          <tr><td class="normal">Filename :</td><td class="normal">archiv.part1.rar</td></tr>
          <tr><td class="normal">Size :</td><td class="normal">1.50 GB</td></tr>
        </table>
        <table><tr><td>Without subscription, you can download only one file at a time</td></tr></table>
        ${if (form) """
        <form action="$base/?$id" method="post" id="f1">
          <input type="hidden" name="adz" value="4711abc&amp;x" />
          <input type="hidden" name="save" value="1" />
          <input type="hidden" name="did" value="0" />
          <input type="submit" name="dl" id="dlb" value="Free download" />
        </form>""" else ""}
        </body></html>
    """.trimIndent()

    /** GET serves the file page, POST the form response. */
    private fun freeDispatcher(page: MockResponse, formReply: MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.method == "POST" && request.path == "/?$id" -> formReply
            request.method == "GET" && request.path == "/?$id&lg=en" -> page
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun failure(block: suspend () -> Unit): Throwable? =
        runCatching { runBlocking { block() } }.exceptionOrNull()

    private fun permanent(t: Throwable?): Boolean = t is HosterException && t.permanent

    @Test
    fun httpFehlerKlassifikation() {
        val hoster = OneFichierHoster()
        // (code, message, JSON body) -> permanent; null = no error at this level
        val faelle = listOf(
            Triple(403, "Flood detected: IP Locked", true) to false,
            Triple(403, null, true) to false,
            Triple(403, "Access denied", true) to false,
            Triple(403, "Not authenticated", true) to true,
            Triple(401, null, true) to true,
            Triple(401, "Not authenticated", true) to true,
            Triple(429, null, true) to false,
            Triple(500, null, true) to false,
            Triple(502, null, false) to false,
            Triple(503, "Please try again later", true) to false,
            Triple(200, "Flood detected, try again in 5 minutes", true) to false,
            Triple(200, null, false) to false,
            Triple(200, null, true) to null
        )
        for ((fall, erwartet) in faelle) {
            val (code, message, isJson) = fall
            val e = hoster.httpFailure(code, message, isJson)
            assertEquals("$fall", erwartet, e?.permanent)
        }
        assertEquals(
            Texts.t("hoster_onefichier_api_error", Texts.t("hoster_not_authenticated", 401)),
            hoster.httpFailure(401, "", true)!!.message
        )
        assertEquals(
            Texts.t("hoster_onefichier_unexpected_response", 200),
            hoster.httpFailure(200, null, false)!!.message
        )
    }



    @Test
    fun ohneApiSchluesselKeineAnfrage() = withServer { server, _, hoster ->
        assertTrue(permanent(failure { hoster.resolve(fileUrl, null) }))
        assertTrue(permanent(failure { hoster.resolve(fileUrl, Account(id = 1, hosterId = "onefichier")) }))
        assertTrue(permanent(failure { hoster.checkAccount(Account(id = 2, hosterId = "onefichier")) }))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun checkLinksLiefertNameGroesseOderOffline() = withServer { server, _, hoster ->
        val antworten = mapOf(
            "abcde12345" to "https://1fichier.com/?abcde12345;Film.mkv;734003200",
            "fghij67890" to "https://1fichier.com/?fghij67890;;NOT FOUND",
            "klmno12345" to "https://1fichier.com/?klmno12345;BAD LINK"
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/check_links.pl") return MockResponse().setResponseCode(404)
                val body = request.body.readUtf8()
                val hit = antworten.entries.firstOrNull { body == "links%5B%5D=https%3A%2F%2F1fichier.com%2F%3F${it.key}" }
                    ?: return MockResponse().setResponseCode(400)
                return MockResponse().setBody(hit.value + "\n").addHeader("Content-Type", "text/plain")
            }
        }
        // Alias domain and upper-case id are normalised before the request
        val online = runBlocking { hoster.checkLink("https://www.desfichiers.com/?ABCDE12345&af=1", null) }
        assertEquals(true, online.online)
        assertEquals("Film.mkv", online.fileName)
        assertEquals(734003200L, online.fileSize)
        val notFound = runBlocking { hoster.checkLink("https://1fichier.com/?fghij67890", null) }
        assertEquals(false, notFound.online)
        assertNull(notFound.fileName)
        assertEquals(false, runBlocking { hoster.checkLink("https://1fichier.com/?klmno12345", null) }.online)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun freeAblaufFormularOhneSaveUndWeiterleitungAufDenFileserver() = withServer { server, base, hoster ->
        server.dispatcher = freeDispatcher(
            html(dateiseite(base)),
            MockResponse().setResponseCode(302).addHeader("Location", "http://a-3.1fichier.com/c1234567890")
        )
        val link = runBlocking { hoster.resolveFree(fileUrl, FreeHints()) }
        // Cleartext is blocked: the file server link is upgraded to HTTPS
        assertEquals("https://a-3.1fichier.com/c1234567890", link.directUrl)
        assertEquals("archiv.part1.rar", link.fileName)
        assertEquals((1.5 * (1L shl 30)).toLong(), link.fileSize)
        assertEquals(fileUrl, link.headers["Referer"])
        assertFalse(link.headers.containsKey("Cookie"))
        assertEquals(2, server.requestCount)
        val seite = server.takeRequest()
        assertEquals("/?$id&lg=en", seite.path)
        assertEquals("LG=en", seite.getHeader("Cookie"))
        assertEquals("$base/", seite.getHeader("Referer"))
        val formular = server.takeRequest()
        assertEquals("POST", formular.method)
        assertEquals("adz=4711abc%26x&did=0", formular.body.readUtf8())
        assertEquals("$base/?$id&lg=en", formular.getHeader("Referer"))
        assertEquals("LG=en", formular.getHeader("Cookie"))
    }

    @Test
    fun hotlinkAntwortIstBereitsDieDatei() = withServer { server, _, hoster ->
        server.enqueue(
            MockResponse().setBody("RAR!").addHeader("Content-Type", "application/octet-stream")
                .addHeader("Content-Disposition", "attachment; filename=\"archiv.part1.rar\"")
        )
        val link = runBlocking { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue(link.directUrl, link.directUrl.startsWith("https://"))
        assertTrue(link.directUrl, link.directUrl.endsWith("/?$id&lg=en"))
        assertEquals(fileUrl, link.headers["Referer"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nurEinDownloadGleichzeitigSperrtErstInDerFormularantwort() = withServer { server, base, hoster ->
        // The notice stands on every free file page: with the form it is no block
        server.dispatcher = freeDispatcher(
            html(dateiseite(base)),
            html("<html><body><div>You already downloading a file. Without subscription, you can download only one file at a time</div></body></html>")
        )
        val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$fehler", fehler is WaitException)
        assertEquals(5 * 60 + 1, (fehler as WaitException).seconds)
        assertEquals(Texts.t("hoster_onefichier_one_at_a_time"), fehler.message)
        assertEquals("form was submitted", 2, server.requestCount)
    }

    @Test
    fun langerCountdownGehtAnDieEngineUndDasFormularBleibt() = withServer { server, base, hoster ->
        server.dispatcher = freeDispatcher(html(dateiseite(base, count = 400)), MockResponse().setResponseCode(500))
        val erste = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$erste", erste is WaitException)
        assertTrue((erste as WaitException).seconds in 395..401)
        assertEquals(Texts.t("hoster_onefichier_free_countdown"), erste.message)
        assertEquals(1, server.requestCount)
        // Second attempt: the parsed form is kept, the page is not loaded again
        val zweite = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$zweite", zweite is WaitException)
        assertTrue((zweite as WaitException).seconds in 390..401)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun dateiseiteOhneFormularSperrenUndOffline() = withServer { server, base, hoster ->
        // (status, body) -> expected exception; 403/5xx are never permanent
        val faelle = listOf(
            Triple(404, "<div class=\"notice alc\">The requested file does not exist</div>", FileOfflineException::class.java),
            Triple(200, "<div class=\"notice alc\">The requested file does not exist</div>", FileOfflineException::class.java),
            Triple(403, "<html><body>Forbidden</body></html>", WaitException::class.java),
            Triple(429, "<html><body>Too many</body></html>", WaitException::class.java),
            Triple(503, "<html><body>Maintenance</body></html>", WaitException::class.java),
            Triple(200, "<div>You must wait 5 minutes</div>", WaitException::class.java),
            Triple(200, "<div>Accès restreint</div>", HosterException::class.java)
        )
        for ((code, body, erwartet) in faelle) {
            server.dispatcher = freeDispatcher(html("<html><body>$body</body></html>", code), MockResponse().setResponseCode(500))
            val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
            assertEquals("$code $body: $fehler", erwartet, fehler?.javaClass)
            assertEquals("$code $body", erwartet == FileOfflineException::class.java, permanent(fehler))
        }
        server.dispatcher = freeDispatcher(html("<html><body>Forbidden</body></html>", 403), MockResponse().setResponseCode(500))
        val sperre = failure { hoster.resolveFree(fileUrl, FreeHints()) } as WaitException
        assertEquals(15 * 60 + 1, sperre.seconds)
        assertEquals(Texts.t("hoster_onefichier_temporarily_blocked", 403), sperre.message)
        // Without the form the notice on the page counts as a block
        server.dispatcher = freeDispatcher(html(dateiseite(base, form = false)), MockResponse().setResponseCode(500))
        val ohneFormular = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$ohneFormular", ohneFormular is WaitException)
        assertEquals(5 * 60 + 1, (ohneFormular as WaitException).seconds)
    }

    @Test
    fun passwortUndCaptchaLaufenUeberDenBrowser() = withServer { server, base, hoster ->
        val faelle = listOf(
            """<input type="password" name="pass" />""" to Texts.t("hoster_onefichier_password_in_browser"),
            """<div class="cf-turnstile" data-sitekey="0x123"></div>""" to Texts.t("hoster_onefichier_confirm_in_browser")
        )
        for ((feld, meldung) in faelle) {
            val seite = dateiseite(base).replace("""<input type="submit"""", "$feld<input type=\"submit\"")
            server.dispatcher = freeDispatcher(html(seite), MockResponse().setResponseCode(500))
            val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
            assertTrue("$feld: $fehler", fehler is CaptchaRequiredException)
            assertEquals("$base/?$id&lg=en", (fehler as CaptchaRequiredException).pageUrl)
            assertEquals(meldung, fehler.message)
        }
    }
}
