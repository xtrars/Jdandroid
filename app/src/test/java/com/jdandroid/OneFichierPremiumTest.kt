package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.OneFichierHoster
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
 * 1fichier premium API against a local server: the 5-minute cache of
 * user/info.cgi (also for failures, flood block) and the token fetch of
 * resolve with and without file/info.cgi.
 */
class OneFichierPremiumTest {

    private val id = "abcde12345"
    private val fileUrl = "https://1fichier.com/?$id"
    private val account = Account(id = 3, hosterId = "onefichier", apiKey = "KEY123")

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    private fun <T> withServer(block: (server: MockWebServer, hoster: OneFichierHoster) -> T): T =
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            block(server, OneFichierHoster("$base/v1", base, "$base/check_links.pl", Http.client))
        }

    private fun apiDispatcher(
        userInfo: MockResponse = MockResponse().setResponseCode(404),
        fileInfo: MockResponse = MockResponse().setResponseCode(404),
        getToken: MockResponse = MockResponse().setResponseCode(404),
        checkLinks: MockResponse = MockResponse().setResponseCode(404)
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/check_links.pl" -> checkLinks
            "/v1/user/info.cgi" -> userInfo
            "/v1/file/info.cgi" -> fileInfo
            "/v1/download/get_token.cgi" -> getToken
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun failure(block: suspend () -> Unit): Throwable? =
        runCatching { runBlocking { block() } }.exceptionOrNull()

    @Test
    fun floodSperreWirdInnerhalbDerCacheZeitNichtWiederholt() = withServer { server, hoster ->
        server.dispatcher = apiDispatcher(
            userInfo = json("""{"status":"KO","message":"Flood detected: IP Locked #1032"}""", 403)
        )
        val erste = failure { hoster.checkAccount(account) }
        assertTrue("$erste", erste is HosterException && !erste.permanent)
        assertEquals(Texts.t("hoster_onefichier_api_error", "Flood detected: IP Locked #1032"), erste!!.message)

        val zweite = failure { hoster.checkAccount(account) }
        assertTrue("$zweite", zweite is HosterException && !zweite.permanent)
        assertEquals(erste.message, zweite!!.message)
        assertEquals(1, server.requestCount)
        val anfrage = server.takeRequest()
        assertEquals("Bearer KEY123", anfrage.getHeader("Authorization"))
        assertEquals("{}", anfrage.body.readUtf8())
    }

    @Test
    fun linkpruefungMeldetSperrstatusStattFehlerseite() = withServer { server, hoster ->
        val sperrseite = """<html><body><h1>IP Locked</h1>
            <a href="https://1fichier.com/?x&amp;a&amp;b">1fichier.com</a></body></html>"""
        server.dispatcher = apiDispatcher(
            checkLinks = MockResponse().setResponseCode(403).setBody(sperrseite).addHeader("Content-Type", "text/html")
        )
        val info = runBlocking { hoster.checkLink(fileUrl, null) }
        assertNull(info.online)
        assertNull(info.fileName)
        assertEquals(Texts.t("hoster_http_status_unknown", 403), info.note)

        server.dispatcher = apiDispatcher(
            checkLinks = MockResponse().setBody("$fileUrl;Film.mkv;734003200\n")
        )
        val online = runBlocking { hoster.checkLink(fileUrl, null) }
        assertEquals(true, online.online)
        assertEquals("Film.mkv", online.fileName)
    }

    @Test
    fun kontostandWirdJeKontoGecacht() = withServer { server, hoster ->
        server.dispatcher = apiDispatcher(
            userInfo = json("""{"status":"OK","offer":"Premium","subscription_end":"2099-01-01 00:00:00","cdn":1,"available_credits_in_gb":2}""")
        )
        val info = runBlocking { hoster.checkAccount(account) }
        assertTrue(info.valid)
        assertTrue(info.trafficUnlimited)
        assertTrue(info.premiumUntil > System.currentTimeMillis())
        assertEquals(hoster.withCdnCredit("Premium/Access", 2.0), info.statusText)
        assertEquals(info, runBlocking { hoster.checkAccount(account) })
        assertEquals(1, server.requestCount)

        // Another account has its own cache entry
        runBlocking { hoster.checkAccount(account.copy(id = 4)) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun resolveHoltTokenAuchOhneDateiInfo() = withServer { server, hoster ->
        server.dispatcher = apiDispatcher(
            fileInfo = MockResponse().setResponseCode(500).setBody("Internal Server Error"),
            getToken = json("""{"status":"OK","url":"https://a-3.1fichier.com/c1"}""")
        )
        val link = runBlocking { hoster.resolve(fileUrl, account) }
        assertEquals("https://a-3.1fichier.com/c1", link.directUrl)
        assertNull(link.fileName)
        assertEquals(-1L, link.fileSize)
        assertNull(link.hash)
        assertEquals(2, server.requestCount)
        assertEquals("/v1/file/info.cgi", server.takeRequest().path)
        val token = server.takeRequest()
        assertEquals("/v1/download/get_token.cgi", token.path)
        assertEquals("Bearer KEY123", token.getHeader("Authorization"))
        assertEquals("""{"url":"$fileUrl"}""", token.body.readUtf8())
    }

    @Test
    fun resolveUebernimmtNameUndGroesse() = withServer { server, hoster ->
        server.dispatcher = apiDispatcher(
            fileInfo = json("""{"status":"OK","filename":"archiv.part1.rar","size":1610612736,"checksum":"abc"}"""),
            getToken = json("""{"status":"OK","url":"https://a-3.1fichier.com/c1"}""")
        )
        val link = runBlocking { hoster.resolve("https://1fichier.com/?ABCDE12345&af=123", account) }
        assertEquals("https://a-3.1fichier.com/c1", link.directUrl)
        assertEquals("archiv.part1.rar", link.fileName)
        assertEquals(1610612736L, link.fileSize)
        assertNull(link.hash)
        assertEquals(2, server.requestCount)
        assertEquals("""{"url":"$fileUrl"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun ungueltigerApiKeyBrichtVorGetTokenAb() = withServer { server, hoster ->
        server.dispatcher = apiDispatcher(
            fileInfo = json("""{"status":"KO","message":"Not authenticated #48"}""", 401),
            getToken = json("""{"status":"OK","url":"https://a-3.1fichier.com/c1"}""")
        )
        val fehler = failure { hoster.resolve(fileUrl, account) }
        assertTrue("$fehler", fehler is HosterException && fehler.permanent)
        assertEquals(1, server.requestCount)

        // Without an API key nothing is requested
        val ohne = failure { hoster.resolve(fileUrl, account.copy(apiKey = null)) }
        assertTrue("$ohne", ohne is HosterException && ohne.permanent)
        assertFalse(ohne!!.message.isNullOrBlank())
        assertEquals(1, server.requestCount)
    }
}
