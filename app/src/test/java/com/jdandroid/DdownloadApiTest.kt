package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.hoster.DdownloadAccountPage
import com.jdandroid.hoster.DdownloadHoster
import com.jdandroid.hoster.FileOfflineException
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.Http
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * ddownload API path (account with key) against a local server: account
 * info in MB, file info, direct link and the classification of odd replies.
 * The account page must never be requested on this path.
 */
class DdownloadApiTest {

    private val code = "chnaz5epeg4t"
    private val fileUrl = "https://ddownload.com/$code"
    private val account = Account(id = 3, hosterId = "ddownload", apiKey = "K")

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    private fun html(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "text/html")

    private val accountInfo = json("""{"status":200,"result":{"premium_expire":"2030-01-05","premium_traffic_left":"197040"}}""")

    private fun fileInfo(status: Int) = json(
        """{"status":200,"result":[{"status":$status,"name":"scn-smps8-S37E02.rar","size":695867392,"filecode":"$code"}]}"""
    )

    private val directLink =
        json("""{"status":200,"result":{"url":"https://s1.ddownload.com/cgi-bin/dl.cgi/token/scn-smps8-S37E02.rar","size":695867392}}""")

    private fun <T> withServer(
        accountReply: MockResponse = accountInfo,
        infoReply: MockResponse = fileInfo(200),
        linkReply: MockResponse = directLink,
        block: (server: MockWebServer, hoster: DdownloadHoster) -> T
    ): T = MockWebServer().use { server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path.orEmpty().substringBefore('?')) {
                    "/api/account/info" -> accountReply
                    "/api/file/info" -> infoReply
                    "/api/file/direct_link" -> linkReply
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        block(server, DdownloadHoster(base, "$base/api", Http.client))
    }

    private fun failure(block: suspend () -> Unit): Throwable? =
        runCatching { runBlocking { block() } }.exceptionOrNull()

    private fun MockWebServer.paths(): List<String> =
        (0 until requestCount).map { takeRequest().path.orEmpty() }

    @Test
    fun kontopruefungLiestKontingentInMegabyte() = withServer { server, hoster ->
        val info = runBlocking { hoster.checkAccount(account) }
        assertTrue(info.valid)
        assertEquals("Premium", info.statusText)
        assertEquals(GregorianCalendar(2030, Calendar.JANUARY, 5).timeInMillis, info.premiumUntil)
        assertEquals(197040L shl 20, info.trafficLeft)
        assertEquals(DdownloadAccountPage.DAILY_QUOTA, info.trafficTotal)
        assertFalse(info.trafficUnlimited)
        val paths = server.paths()
        assertTrue("$paths", paths.all { it.startsWith("/api/account/info?") && it.contains("key=K") })
    }

    @Test
    fun kontopruefungUnbegrenztUndPremiumOhneAblaufdatum() {
        withServer(accountReply = json("""{"status":200,"result":{"premium_expire":"","premium_traffic_left":"unlimited"}}""")) { _, hoster ->
            val info = runBlocking { hoster.checkAccount(account) }
            assertTrue(info.valid)
            assertTrue(info.trafficUnlimited)
            assertEquals(-1L, info.trafficLeft)
            assertEquals("Premium", info.statusText)
        }
        withServer(accountReply = json("""{"status":200,"result":{"premium_expire":"someday","premium_traffic_left":"1024"}}""")) { _, hoster ->
            val info = runBlocking { hoster.checkAccount(account) }
            assertEquals("Premium", info.statusText)
            assertEquals(0L, info.premiumUntil)
            assertEquals(1L shl 30, info.trafficLeft)
        }
    }

    @Test
    fun kontopruefungMitHtmlStattJsonIstVoruebergehend() =
        withServer(accountReply = html("<html><title>Just a moment...</title></html>", 503)) { _, hoster ->
            val fehler = failure { hoster.checkAccount(account) }
            assertTrue("$fehler", fehler is HosterException && !fehler.permanent)
            assertEquals(Texts.t("hoster_ddownload_api_not_json", 503), fehler!!.message)
        }

    @Test
    fun ungueltigerSchluesselIstDauerhaft() =
        withServer(accountReply = json("""{"status":403,"msg":"invalid key"}""")) { _, hoster ->
            val fehler = failure { hoster.checkAccount(account) }
            assertTrue("$fehler", fehler is HosterException && fehler.permanent)
        }

    @Test
    fun aufloesenUeberApiLiefertDirektlinkOhneKontoseite() = withServer { server, hoster ->
        val link = runBlocking { hoster.resolve(fileUrl, account) }
        assertEquals("https://s1.ddownload.com/cgi-bin/dl.cgi/token/scn-smps8-S37E02.rar", link.directUrl)
        assertEquals("scn-smps8-S37E02.rar", link.fileName)
        assertEquals(695867392L, link.fileSize)
        val paths = server.paths()
        assertEquals("$paths", 2, paths.size)
        assertTrue("$paths", paths.none { it.contains("op=my_account") })
        assertTrue("$paths", paths.all { it.contains("file_code=$code") })
    }

    @Test
    fun linkpruefungUeberApi() {
        withServer { _, hoster ->
            val info = runBlocking { hoster.checkLink(fileUrl, account) }
            assertEquals(true, info.online)
            assertEquals("scn-smps8-S37E02.rar", info.fileName)
            assertEquals(695867392L, info.fileSize)
        }
        withServer(infoReply = fileInfo(404)) { _, hoster ->
            val info = runBlocking { hoster.checkLink(fileUrl, account) }
            assertEquals(false, info.online)
            assertEquals(Texts.t("hoster_file_not_found"), info.note)
        }
    }

    @Test
    fun dateiInfo404IstOffline() = withServer(infoReply = fileInfo(404)) { _, hoster ->
        assertTrue(failure { hoster.resolve(fileUrl, account) } is FileOfflineException)
    }

    @Test
    fun direktlinkOhneResultOderUrlIstVoruebergehend() {
        withServer(linkReply = json("""{"status":200,"msg":"OK"}""")) { _, hoster ->
            val fehler = failure { hoster.resolve(fileUrl, account) }
            assertTrue("$fehler", fehler is HosterException && !fehler.permanent)
            assertEquals(Texts.t("hoster_ddownload_no_download_url"), fehler!!.message)
        }
        withServer(linkReply = json("""{"status":200,"result":{"url":""}}""")) { _, hoster ->
            val fehler = failure { hoster.resolve(fileUrl, account) }
            assertTrue("$fehler", fehler is HosterException && !fehler.permanent)
            assertEquals(Texts.t("hoster_ddownload_no_download_url_premium"), fehler!!.message)
        }
        withServer(linkReply = html("<html>Error</html>", 502)) { _, hoster ->
            val fehler = failure { hoster.resolve(fileUrl, account) }
            assertTrue("$fehler", fehler is HosterException && !fehler.permanent)
        }
    }
}
