package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.DdownloadHoster
import com.jdandroid.hoster.FileOfflineException
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.Http
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
import java.util.Calendar
import java.util.GregorianCalendar

/** Expected epoch in the default time zone, as SimpleDateFormat uses it in the parser. */
private fun epoch(year: Int, month: Int, day: Int): Long = GregorianCalendar(year, month, day).timeInMillis

/**
 * ddownload against a local server: account page with the browser session,
 * premium download form with its redirect chain, and the free flow
 * (Turnstile, blocks, form errors). The API cannot run here: org.json is
 * only a stub on the JVM, its classification is tested through
 * [DdownloadHoster.apiFailure].
 */
class DdownloadFlowTest {

    private val code = "chnaz5epeg4t"
    private val fileUrl = "https://ddownload.com/$code"
    private val session = Account(id = 3, hosterId = "ddownload", cookies = "xfss=abc123; lang=german")

    private fun html(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "text/html; charset=UTF-8")

    private fun redirect(location: String): MockResponse =
        MockResponse().setResponseCode(302).addHeader("Location", location)

    private fun <T> withServer(block: (server: MockWebServer, base: String, hoster: DdownloadHoster) -> T): T =
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            block(server, base, DdownloadHoster(base, "$base/api", Http.client))
        }

    /** Account page of a logged-in Ultimate account; the quota is mislabelled as on the real page. */
    private val kontoseite = """
        <html><body><a href="/?op=logout">Logout</a>
        <div>Account-Status</div><div>Ultimate</div><div>Aktiv bis 2 December 2030</div>
        <div>Verfügbare Daten</div><div>197040 GB</div>
        <a>Traffic kaufen</a><div>200 GB + Daten &euro;15.99</div></body></html>
    """.trimIndent()

    private val ohneLogin = """<html><body><a href="/login.html">Login</a><div>Account-Status</div></body></html>"""

    /** File page with the download form; [captcha] and [countdown] are inserted as given. */
    private fun dateiseite(captcha: String = "", countdown: String = "", extra: String = "") = """
        <html><head><title>Download scn-smps8-S37E02 rar</title></head><body>
        <form name="F1" method="POST" action="">
            <input type="hidden" name="op" value="download2">
            <input type="hidden" name="id" value="$code">
            <input type="hidden" name="rand" value="4n4yng6b5hgxdjgygbu7oom6r5apolomedgsk57f2e">
            <input type="hidden" name="referer" value="">
            <input type="hidden" name="method_free" value="">
            <input type="hidden" name="method_premium" value="">
            <h2 class="dk-dl-name">scn-smps8-S37E02.rar</h2>
            <p class="dk-dl-size">663.63 MB</p>
            $captcha
            $countdown
            $extra
            <button type="button" id="downloadbtn" class="dk-dl-btn">Normaler Download</button>
        </form>
        </body></html>
    """.trimIndent()

    private val turnstile = """<div class="cf-turnstile" data-sitekey="0x4AAAAAABm53D0OJNkESa1O"></div>"""

    private fun countdown(seconds: Int) = """<span class="dk-countdown-num">$seconds</span>"""

    private fun alert(text: String) = """<div class="dk-dl-alert">$text</div>"""

    /** Serves the site by method and path: [account] page, file page, form response and further hops. */
    private fun siteDispatcher(
        account: MockResponse = html(kontoseite),
        page: MockResponse = html(dateiseite()),
        form: MockResponse = MockResponse().setResponseCode(500),
        hops: Map<String, MockResponse> = emptyMap()
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path == "/?op=my_account" -> account
                path == "/$code" && request.method == "POST" -> form
                path == "/$code" -> page
                path in hops -> hops.getValue(path)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun failure(block: suspend () -> Unit): Throwable? =
        runCatching { runBlocking { block() } }.exceptionOrNull()

    private fun permanent(t: Throwable?): Boolean = t is HosterException && t.permanent

    @Test
    fun kontoseiteMitFalschBeschriftetemKontingentLiefertMegabyte() = withServer { server, _, hoster ->
        server.enqueue(html(kontoseite))
        val info = runBlocking { hoster.checkAccount(session) }
        assertTrue(info.valid)
        assertEquals("Ultimate", info.statusText)
        assertEquals(epoch(2030, Calendar.DECEMBER, 2), info.premiumUntil)
        // "197040 GB" means MB: 192.4 GiB of the 200 GB daily quota
        assertEquals(197040L shl 20, info.trafficLeft)
        assertEquals(200L shl 30, info.trafficTotal)
        assertFalse(info.trafficUnlimited)
        val request = server.takeRequest()
        assertEquals("/?op=my_account", request.path)
        assertEquals("xfss=abc123; lang=german", request.getHeader("Cookie"))
    }

    @Test
    fun sitzungOhneLogoutLinkZweimalIstAbgelaufen() = withServer { server, _, hoster ->
        server.enqueue(html(ohneLogin))
        server.enqueue(html(ohneLogin))
        val fehler = failure { hoster.checkAccount(session) }
        assertTrue("$fehler", permanent(fehler))
        assertEquals(Texts.t("hoster_ddownload_session_expired"), fehler!!.message)
        // One retry with freshly seeded browser cookies, then give up
        assertEquals(2, server.requestCount)
        assertEquals("xfss=abc123; lang=german", server.takeRequest().getHeader("Cookie"))
        assertEquals("xfss=abc123; lang=german", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun kontoseiteMitSperreOderServerfehlerIstVoruebergehend() {
        for (code in listOf(403, 429, 500, 502, 503)) {
            withServer { server, _, hoster ->
                server.enqueue(html("<html><body>Error</body></html>", code))
                server.enqueue(html("<html><body>Error</body></html>", code))
                val fehler = failure { hoster.checkAccount(session) }
                assertTrue("$code: $fehler", fehler is HosterException && !fehler.permanent)
                assertEquals("$code", 1, server.requestCount)
            }
        }
    }

    @Test
    fun ohneSitzungUndOhneSchluesselKeineAnfrage() = withServer { server, _, hoster ->
        val fehler = failure { hoster.checkAccount(Account(id = 9, hosterId = "ddownload")) }
        assertTrue("$fehler", permanent(fehler))
        assertEquals(Texts.t("hoster_ddownload_no_login"), fehler!!.message)
        assertTrue(permanent(failure { hoster.resolve(fileUrl, null) }))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun formularantwortFolgtDerWeiterleitungsketteOhneDieDateiZuLaden() = withServer { server, base, hoster ->
        val direct = "https://s12.ddownload.com/d/abc/scn-smps8-S37E02.rar"
        server.dispatcher = siteDispatcher(
            form = redirect("/cgi-bin/dl.cgi"),
            hops = mapOf("/cgi-bin/dl.cgi" to redirect(direct))
        )
        val link = runBlocking { hoster.resolve(fileUrl, session) }
        assertEquals(direct, link.directUrl)
        assertEquals("scn-smps8-S37E02.rar", link.fileName)
        // Account page, file page, form, relative hop; the file server is never called
        assertEquals(4, server.requestCount)
        assertEquals("/?op=my_account", server.takeRequest().path)
        val seite = server.takeRequest()
        assertEquals("/$code", seite.path)
        assertEquals("xfss=abc123; lang=german", seite.getHeader("Cookie"))
        val formular = server.takeRequest()
        assertEquals("POST", formular.method)
        assertEquals("$base/$code", formular.getHeader("Referer"))
        val felder = formular.body.readUtf8().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("download2", felder["op"])
        assertEquals(code, felder["id"])
        assertEquals("4n4yng6b5hgxdjgygbu7oom6r5apolomedgsk57f2e", felder["rand"])
        assertEquals("1", felder["method_premium"])
        assertEquals("", felder["method_free"])
        val hop = server.takeRequest()
        assertEquals("/cgi-bin/dl.cgi", hop.path)
        assertEquals("$base/$code", hop.getHeader("Referer"))
    }

    @Test
    fun weiterleitungsPingPongEndetNachWenigenHops() = withServer { server, _, hoster ->
        server.dispatcher = siteDispatcher(
            form = redirect("/a"),
            hops = mapOf("/a" to redirect("/b"), "/b" to redirect("/a"))
        )
        val fehler = failure { hoster.resolve(fileUrl, session) }
        assertTrue("$fehler", fehler is HosterException)
        assertTrue(fehler!!.message!!, fehler.message!!.contains(Texts.t("hoster_hint_redirect_without_file")))
        // Account page, file page, form and at most six hops
        assertTrue("${server.requestCount}", server.requestCount in 4..9)
    }

    @Test
    fun dateiNichtGefundenIstDauerhaft() = withServer { server, _, hoster ->
        server.dispatcher = siteDispatcher(page = html("<html><body><h1>File Not Found</h1></body></html>"))
        assertTrue(failure { hoster.resolve(fileUrl, session) } is FileOfflineException)
        assertTrue(failure { hoster.resolveFree(fileUrl, FreeHints()) } is FileOfflineException)
        val info = runBlocking { hoster.checkLink(fileUrl, null) }
        assertEquals(false, info.online)
    }

    @Test
    fun formularantwortMitSperreOderServerfehlerIstVoruebergehend() {
        for (code in listOf(403, 429, 500, 503)) {
            withServer { server, _, hoster ->
                server.dispatcher = siteDispatcher(form = html("<html><body>Error</body></html>", code))
                val fehler = failure { hoster.resolve(fileUrl, session) }
                assertTrue("$code: $fehler", fehler is HosterException && !fehler.permanent)
                assertFalse("$code", fehler is FileOfflineException)
            }
        }
    }

    @Test
    fun formularantwortMitAbgelaufenerSitzungIstVoruebergehend() {
        for (text in listOf("Expired download session", "Skipped countdown")) {
            withServer { server, _, hoster ->
                server.dispatcher = siteDispatcher(form = html(dateiseite(extra = alert(text))))
                val fehler = failure { hoster.resolve(fileUrl, session) }
                assertTrue("$text: $fehler", fehler is HosterException && !fehler.permanent)
                assertFalse(text, fehler is FileOfflineException)
            }
        }
    }

    @Test
    fun dateiAlsFormularantwortIstKeinDirektlink() {
        val datei = MockResponse().setBody("RAR!").addHeader("Content-Type", "application/octet-stream")
            .addHeader("Content-Disposition", "attachment; filename=\"scn-smps8-S37E02.rar\"")
        withServer { server, base, hoster ->
            server.dispatcher = siteDispatcher(form = datei)
            // The POST address fetched via GET would only serve the file page again
            val premium = failure { hoster.resolve(fileUrl, session) }
            assertTrue("$premium", premium is HosterException && !premium.permanent)
            assertEquals(Texts.t("hoster_ddownload_no_direct_link", 200), premium!!.message)
            val free = failure { hoster.resolveFree(fileUrl, FreeHints()) }
            assertTrue("$free", free is HosterException && !free.permanent)
            assertEquals(Texts.t("hoster_ddownload_no_direct_link", 200), free!!.message)
            assertTrue(server.requestCount >= 5)
        }
        withServer { server, base, hoster ->
            // After a redirect hop the fetched address is the link
            server.dispatcher = siteDispatcher(form = redirect("/cgi-bin/dl.cgi"), hops = mapOf("/cgi-bin/dl.cgi" to datei))
            assertEquals("$base/cgi-bin/dl.cgi", runBlocking { hoster.resolve(fileUrl, session) }.directUrl)
            assertEquals("$base/cgi-bin/dl.cgi", runBlocking { hoster.resolveFree(fileUrl, FreeHints()) }.directUrl)
        }
    }

    @Test
    fun apiFehlerKlassifikation() {
        val hoster = DdownloadHoster()
        // (status, msg) -> permanent
        val faelle = listOf(
            Triple(404, "", true),
            Triple(400, "File not found", true),
            Triple(403, "Invalid key", true),
            Triple(403, "Wrong key", true),
            Triple(400, "No such file", true),
            Triple(403, "Daily download limit reached", false),
            Triple(403, "Access denied", false),
            Triple(429, "Too many requests", false),
            Triple(500, "", false),
            Triple(503, "Service unavailable", false)
        )
        for ((status, msg, erwartet) in faelle) {
            assertEquals("$status $msg", erwartet, hoster.apiFailure(status, msg).permanent)
        }
        assertEquals(
            Texts.t("hoster_ddownload_api_error", Texts.t("hoster_http_status", 500)),
            hoster.apiFailure(500, "").message
        )
    }


    @Test
    fun turnstileImFormularBrauchtDenBrowser() = withServer { server, base, hoster ->
        server.dispatcher = siteDispatcher(page = html(dateiseite(captcha = turnstile, countdown = countdown(60))))
        val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$fehler", fehler is CaptchaRequiredException)
        assertEquals("$base/$code", (fehler as CaptchaRequiredException).pageUrl)
        assertEquals(Texts.t("hoster_ddownload_captcha_browser", "Turnstile"), fehler.message)
        // The form is never submitted without a token
        assertEquals(1, server.requestCount)
    }

    @Test
    fun download2OhneTokenAntwortetWrongCaptcha() = withServer { server, base, hoster ->
        server.dispatcher = siteDispatcher(
            page = html(dateiseite()),
            form = html(dateiseite(extra = alert("Wrong captcha")))
        )
        val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$fehler", fehler is CaptchaRequiredException)
        assertEquals("$base/$code", (fehler as CaptchaRequiredException).pageUrl)
        assertEquals(Texts.t("hoster_ddownload_captcha_rejected"), fehler.message)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val formular = server.takeRequest()
        assertEquals("POST", formular.method)
        val felder = formular.body.readUtf8().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("download2", felder["op"])
        assertEquals("4n4yng6b5hgxdjgygbu7oom6r5apolomedgsk57f2e", felder["rand"])
        assertEquals("Free+Download", felder["method_free"])
        assertEquals("", felder["method_premium"])
        assertFalse(felder.containsKey("cf-turnstile-response"))
    }

    @Test
    fun formularfehlerImAlert() {
        // alert text -> expected exception and wait
        val faelle = listOf(
            Triple("Expired download session", HosterException::class.java, 0),
            Triple("Skipped countdown", WaitException::class.java, 61),
            Triple("You have reached the download-limit: 8 minutes", WaitException::class.java, 8 * 60 + 1)
        )
        for ((text, erwartet, seconds) in faelle) {
            withServer { server, _, hoster ->
                server.dispatcher = siteDispatcher(form = html(dateiseite(extra = alert(text))))
                val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
                assertEquals("$text: $fehler", erwartet, fehler?.javaClass)
                assertFalse(text, permanent(fehler))
                if (fehler is WaitException) assertEquals(text, seconds, fehler.seconds)
            }
        }
    }

    @Test
    fun sperreMitWaitSecondsWirdZurWartezeitOhneFormular() = withServer { server, _, hoster ->
        val gesperrt = dateiseite().replace(
            """class="dk-dl-btn"""",
            """class="dk-dl-btn dk-btn-blocked" data-toast-msg="You have to wait 1 hour, 2 minutes, 3 seconds till next download" data-wait-seconds="3723""""
        )
        server.dispatcher = siteDispatcher(page = html(gesperrt))
        val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$fehler", fehler is WaitException)
        assertEquals(3724, (fehler as WaitException).seconds)
        assertEquals(Texts.t("hoster_ddownload_free_locked"), fehler.message)
        assertEquals(1, server.requestCount)
        // A block is not remembered: the next attempt reads the page again
        assertTrue(failure { hoster.resolveFree(fileUrl, FreeHints()) } is WaitException)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun gemerktesFormularGiltJeDateicode() = withServer { server, _, hoster ->
        val anderer = "abc123def456"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/$code", "/$anderer" -> html(dateiseite(countdown = countdown(600)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val erste = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$erste", erste is WaitException)
        assertEquals(601, (erste as WaitException).seconds)
        assertEquals(1, server.requestCount)
        assertTrue(failure { hoster.resolveFree("https://ddownload.com/$anderer", FreeHints()) } is WaitException)
        assertEquals(2, server.requestCount)
        // Remembered form for the first code: no page reload, remaining time
        val dritte = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$dritte", dritte is WaitException)
        assertTrue((dritte as WaitException).seconds in 590..601)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun countdownUeberFuenfSekundenGehtAnDieEngineUndDasFormularBleibt() = withServer { server, _, hoster ->
        server.dispatcher = siteDispatcher(page = html(dateiseite(countdown = countdown(30))))
        val erste = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$erste", erste is WaitException)
        assertEquals(31, (erste as WaitException).seconds)
        assertEquals(Texts.t("hoster_ddownload_free_countdown"), erste.message)
        assertEquals(1, server.requestCount)
        // Second attempt: the remembered form, no page reload, remaining time
        val zweite = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$zweite", zweite is WaitException)
        assertTrue((zweite as WaitException).seconds in 25..31)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun kurzerCountdownWirdAbgewartetUndDasFormularVerbraucht() = withServer { server, _, hoster ->
        server.dispatcher = siteDispatcher(
            page = html(dateiseite(countdown = countdown(1))),
            form = html(dateiseite(extra = alert("Wrong captcha")))
        )
        val start = System.currentTimeMillis()
        val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$fehler", fehler is CaptchaRequiredException)
        assertTrue(System.currentTimeMillis() - start >= 1500)
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        // The form is used up: the next attempt reads the page again
        assertTrue(failure { hoster.resolveFree(fileUrl, FreeHints()) } is CaptchaRequiredException)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun titelFallbackBehaeltBindestricheImNamen() {
        val hoster = DdownloadHoster()
        assertEquals(
            "scn-smps8-S37E02.rar",
            hoster.pageFileName("<html><head><title>Download scn-smps8-S37E02 rar</title></head></html>")
        )
        assertEquals(
            "scn-smps8-S37E02.rar",
            hoster.pageFileName("<html><head><title>Download scn-smps8-S37E02 rar - DDownload</title></head></html>")
        )
        assertEquals(
            "Serie.S01E01.mkv",
            hoster.pageFileName("<html><head><title>Download Serie.S01E01.mkv | ddownload</title></head></html>")
        )
        assertNull(hoster.pageFileName("<html><head><title>Download - ddownload</title></head></html>"))
    }
}
