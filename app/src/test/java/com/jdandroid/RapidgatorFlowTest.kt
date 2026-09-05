package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.FileOfflineException
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.RapidgatorHoster
import com.jdandroid.hoster.WaitException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rapidgator free flow against a local server: file page, AjaxStartTimer,
 * AjaxGetDownloadLink, captcha page. The API cannot run here: org.json is
 * only a stub on the JVM, its classification is covered by
 * [RapidgatorFailureTest].
 */
class RapidgatorFlowTest {

    private val fileId = "0d348b3c239fe48ea3fed28b8810190d"
    private val fileUrl = "https://rapidgator.net/file/$fileId/c20-fundamentals.part02.rar.html"

    private fun html(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "text/html; charset=utf-8")

    private fun json(body: String): MockResponse =
        MockResponse().setBody(body).addHeader("Content-Type", "application/json")

    private fun <T> withServer(block: (server: MockWebServer, hoster: RapidgatorHoster) -> T): T =
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            block(server, RapidgatorHoster("$base/api", base, Http.client))
        }

    /** File page with the timer variables; [secs] as the server states it. */
    private fun dateiseite(secs: Int) = """
        <html><head><title>Download file c20-fundamentals.part02.rar</title></head><body>
        <p><strong>Downloading:</strong><a href="">c20-fundamentals.part02.rar</a></p>
        <div>File size: <strong>1 GB</strong></div>
        <script type="text/javascript">
            var startTimerUrl = '/download/AjaxStartTimer';
            var getDownloadUrl = '/download/AjaxGetDownloadLink';
            var captchaUrl = '/download/captcha';
            var secs = $secs;
            var fid = 638602268;
        </script>
        </body></html>
    """.trimIndent()

    private val captchaSeite = """
        <html><body><form id="captchaform" action="/download/captcha" method="post">
        <input id="DownloadCaptchaForm_verifyCode" type="hidden" name="DownloadCaptchaForm[verifyCode]" />
        <div class="cf-turnstile" data-sitekey="0x4AAAAAACGwIXkmZ2lsGdCV"></div>
        </form></body></html>
    """.trimIndent()

    /** Serves the free flow by path; [startTimer] and [captcha] are replaceable. */
    private fun freeDispatcher(
        page: MockResponse,
        startTimer: MockResponse = json("""{"state":"started","sid":"SID1","code":""}"""),
        getLink: MockResponse = json("""{"state":"done","code":""}"""),
        captcha: MockResponse = html(captchaSeite)
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.startsWith("/file/$fileId") -> page
                path.startsWith("/download/AjaxStartTimer") -> startTimer
                path.startsWith("/download/AjaxGetDownloadLink") -> getLink
                path.startsWith("/download/captcha") -> captcha
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun failure(block: suspend () -> Unit): Throwable? =
        runCatching { runBlocking { block() } }.exceptionOrNull()


    @Test
    fun ohneKontoKeineAnfrage() = withServer { server, hoster ->
        val fehler = failure { hoster.resolve(fileUrl, null) }
        assertTrue("$fehler", fehler is HosterException && fehler.permanent)
        assertEquals(Texts.t("hoster_rapidgator_premium_required"), fehler!!.message)
        val info = runBlocking { hoster.checkLink(fileUrl, null) }
        assertNull(info.online)
        assertEquals(Texts.t("hoster_rapidgator_check_needs_account"), info.note)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun timerUeber180SekundenGehtAlsWartezeitAnDieEngine() = withServer { server, hoster ->
        server.dispatcher = freeDispatcher(html(dateiseite(600)))
        val erste = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$erste", erste is WaitException)
        assertEquals(601, (erste as WaitException).seconds)
        assertEquals(Texts.t("hoster_rapidgator_free_wait"), erste.message)
        assertEquals(2, server.requestCount)
        val seite = server.takeRequest()
        assertEquals("/file/$fileId/c20-fundamentals.part02.rar.html", seite.path)
        assertEquals("lang=en", seite.getHeader("Cookie"))
        assertEquals("${server.url("/")}", seite.getHeader("Referer"))
        val timer = server.takeRequest()
        assertEquals("/download/AjaxStartTimer?fid=638602268", timer.path)
        assertEquals("XMLHttpRequest", timer.getHeader("X-Requested-With"))

        // Timer still running: the remaining time goes to the engine without a new page or timer
        val zweite = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$zweite", zweite is WaitException)
        assertTrue((zweite as WaitException).seconds in 590..601)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun captchaSeiteLiefertCaptchaRequiredMitSitzungsCookies() = withServer { server, hoster ->
        server.dispatcher = freeDispatcher(html(dateiseite(0)).addHeader("Set-Cookie", "PHPSESSID=s3ss10n; Path=/"))
        val erste = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$erste", erste is WaitException)
        assertEquals(1, (erste as WaitException).seconds)
        server.takeRequest()
        server.takeRequest()

        val zweite = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$zweite", zweite is CaptchaRequiredException)
        val captcha = zweite as CaptchaRequiredException
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/download/captcha", captcha.pageUrl)
        assertEquals(base, captcha.cookieUrl)
        assertTrue(captcha.cookies.toString(), captcha.cookies.any { it.startsWith("PHPSESSID=s3ss10n") })
        assertEquals(Texts.t("hoster_rapidgator_captcha_browser"), captcha.message)
        assertEquals(4, server.requestCount)
        val unlock = server.takeRequest()
        assertEquals("/download/AjaxGetDownloadLink?sid=SID1", unlock.path)
        assertTrue(unlock.getHeader("Cookie").orEmpty(), unlock.getHeader("Cookie").orEmpty().contains("PHPSESSID=s3ss10n"))
        assertEquals("/download/captcha", server.takeRequest().path)
    }

    @Test
    fun captchaSeiteMitDirektlinkLiefertDenLinkOhneCaptcha() = withServer { server, hoster ->
        val direct = "https://pr5.rapidgator.net//?r=download/index&session_id=Ab12Cd34"
        val fertig = """<html><body><script>location.href = '$direct';</script></body></html>"""
        server.dispatcher = freeDispatcher(html(dateiseite(0)), captcha = html(fertig))
        assertTrue(failure { hoster.resolveFree(fileUrl, FreeHints()) } is WaitException)
        val link = runBlocking { hoster.resolveFree(fileUrl, FreeHints()) }
        assertEquals(direct, link.directUrl)
        assertEquals("c20-fundamentals.part02.rar", link.fileName)
        assertEquals(1L shl 30, link.fileSize)
        assertEquals("${server.url("/").toString().trimEnd('/')}/download/captcha", link.headers["Referer"])
        assertNotNull(link.headers["User-Agent"])
        // Session cookies of the local server do not apply to the file server
        assertFalse(link.headers.containsKey("Cookie"))
    }

    @Test
    fun timerAbgelehntMitLimitWirdZurWartezeit() = withServer { server, hoster ->
        val faelle = listOf(
            "You have reached your daily downloads limit" to 3 * 3600,
            "Delay between downloads must be not less than 120 minutes" to 120 * 60 + 1,
            "Try again in 5 minutes" to 5 * 60 + 1
        )
        for ((code, seconds) in faelle) {
            server.dispatcher = freeDispatcher(
                html(dateiseite(180)),
                startTimer = json("""{"state":"error","code":"$code","0":"step3"}""")
            )
            val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
            assertTrue("$code: $fehler", fehler is WaitException)
            assertEquals(code, seconds, (fehler as WaitException).seconds)
        }
    }

    /**
     * The server rejects the unlock or the captcha page; [perRestart] requests
     * per attempt (file page and timer again), [perRejection] until the rejection.
     */
    private fun neustartBisAbbruch(
        server: MockWebServer,
        hoster: RapidgatorHoster,
        perRejection: Int,
        grund: String
    ) {
        val erste = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$erste", erste is WaitException)
        assertEquals(1, (erste as WaitException).seconds)
        assertEquals(2, server.requestCount)

        // Rejected three times: the session is discarded and file page and timer fetched again
        repeat(3) {
            val vorher = server.requestCount
            val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
            assertTrue("$it: $fehler", fehler is WaitException)
            assertEquals(1, (fehler as WaitException).seconds)
            assertEquals(vorher + perRejection + 2, server.requestCount)
        }

        // Fourth rejection: give up for now without fetching a new page
        val vorher = server.requestCount
        val abbruch = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$abbruch", abbruch is HosterException && !abbruch.permanent)
        assertEquals(Texts.t("hoster_rapidgator_flow_not_accepted", grund), abbruch!!.message)
        assertEquals(vorher + perRejection, server.requestCount)

        // Counter reset: the next attempt starts from the file page again
        val danach = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$danach", danach is WaitException)
        assertEquals(vorher + perRejection + 2, server.requestCount)
    }

    @Test
    fun ungueltigerTimerStartetNeuUndGibtNachDreiNeustartsAuf() = withServer { server, hoster ->
        val code = "You didn`t wait specified time. Try again or contact to administrator"
        server.dispatcher = freeDispatcher(
            html(dateiseite(0)),
            getLink = json("""{"state":"error","code":"$code","0":"step3"}""")
        )
        neustartBisAbbruch(server, hoster, perRejection = 1, grund = Texts.t("hoster_rapidgator_unlock_rejected", code))
    }

    @Test
    fun weiterleitungDerCaptchaSeiteStartetNeuUndGibtNachDreiNeustartsAuf() = withServer { server, hoster ->
        server.dispatcher = freeDispatcher(
            html(dateiseite(0)),
            captcha = MockResponse().setResponseCode(302).addHeader("Location", "/file/$fileId")
        )
        neustartBisAbbruch(server, hoster, perRejection = 2, grund = Texts.t("hoster_rapidgator_captcha_page_redirect"))
    }

    @Test
    fun dateiseiteOfflineOderNichtErreichbar() = withServer { server, hoster ->
        server.enqueue(html("<html><body><h1>404 File not found</h1></body></html>", 404))
        assertTrue(failure { hoster.resolveFree(fileUrl, FreeHints()) } is FileOfflineException)
        // Unknown id: redirect to the premium article, followed by the client
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/article/premium"))
        server.enqueue(html("<html><body>Premium</body></html>"))
        assertTrue(failure { hoster.resolveFree(fileUrl, FreeHints()) } is FileOfflineException)
        // Server error: temporary
        server.enqueue(html("<html><body>Service Unavailable</body></html>", 503))
        val fehler = failure { hoster.resolveFree(fileUrl, FreeHints()) }
        assertTrue("$fehler", fehler is HosterException && !fehler.permanent && fehler !is FileOfflineException)
    }
}
