package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.RapidgatorHoster
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
 * Rapidgator premium API against a local server: login, token cache,
 * re-login only on an expired session (401) and never on a block (403).
 */
class RapidgatorPremiumFlowTest {

    private val fileId = "0d348b3c239fe48ea3fed28b8810190d"
    private val fileUrl = "https://rapidgator.net/file/$fileId/archiv.part1.rar.html"
    private val account = Account(id = 7, hosterId = "rapidgator", username = "user", password = "secret")

    private fun json(body: String): MockResponse =
        MockResponse().setBody(body).addHeader("Content-Type", "application/json")

    private fun <T> withServer(block: (server: MockWebServer, hoster: RapidgatorHoster) -> T): T =
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            block(server, RapidgatorHoster("$base/api", base, Http.client))
        }

    private fun RecordedRequest.form(): Map<String, String> =
        body.readUtf8().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun loginReply(token: String) = json(
        """{"status":200,"details":null,"response":{"token":"$token",""" +
            """"user":{"is_premium":true,"premium_end_time":4102444800,"traffic":{"left":1,"total":2}}}}"""
    )

    private fun error(status: Int, details: String) =
        json("""{"status":$status,"details":"$details","response":null}""")

    private val fileInfo = json(
        """{"status":200,"details":null,"response":{"file":{"name":"archiv.part1.rar","size":1610612736,""" +
            """"hash":"0123456789ABCDEF0123456789ABCDEF"}}}"""
    )

    /**
     * API by path: every login hands out the next token T1, T2, …;
     * [download] decides per token what file/download answers.
     */
    private class ApiDispatcher(
        private val loginReply: (String) -> MockResponse,
        private val download: (token: String) -> MockResponse,
        private val userInfo: (token: String) -> MockResponse = { MockResponse().setResponseCode(404) },
        private val fileInfo: MockResponse
    ) : Dispatcher() {
        var logins = 0
        override fun dispatch(request: RecordedRequest): MockResponse {
            val form = request.body.clone().readUtf8().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            return when (request.path) {
                "/api/user/login" -> loginReply("T${++logins}")
                "/api/file/download" -> download(form["token"].orEmpty())
                "/api/user/info" -> userInfo(form["token"].orEmpty())
                "/api/file/info" -> fileInfo
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun failure(block: suspend () -> Unit): Throwable? =
        runCatching { runBlocking { block() } }.exceptionOrNull()

    @Test
    fun abgelaufenesTokenLoestGenauEinenNeuenLoginAus() = withServer { server, hoster ->
        val api = ApiDispatcher(
            loginReply = ::loginReply,
            download = { token ->
                if (token == "T2") json("""{"status":200,"details":null,"response":{"download_url":"https://pr5.rapidgator.net/x/archiv.part1.rar"}}""")
                else error(401, "Session not exist")
            },
            fileInfo = fileInfo
        )
        server.dispatcher = api

        // Login through the account check caches T1; the login response already carries the user
        val info = runBlocking { hoster.checkAccount(account) }
        assertTrue(info.valid)
        assertEquals("Premium", info.statusText)
        assertEquals(1L, info.trafficLeft)
        assertEquals(2L, info.trafficTotal)
        assertEquals(4102444800L * 1000, info.premiumUntil)
        assertEquals(1, server.requestCount)
        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals(mapOf("login" to "user", "password" to "secret"), login.form())

        val link = runBlocking { hoster.resolve(fileUrl, account) }
        assertEquals("https://pr5.rapidgator.net/x/archiv.part1.rar", link.directUrl)
        assertEquals("archiv.part1.rar", link.fileName)
        assertEquals(1610612736L, link.fileSize)
        assertEquals("0123456789abcdef0123456789abcdef", link.hash)
        assertEquals(2, api.logins)
        // download(T1) 401, login, download(T2), file/info
        assertEquals(5, server.requestCount)
        assertEquals(mapOf("file_id" to fileId, "token" to "T1"), server.takeRequest().form())
        assertEquals("/api/user/login", server.takeRequest().path)
        assertEquals(mapOf("file_id" to fileId, "token" to "T2"), server.takeRequest().form())
        assertEquals(mapOf("file_id" to fileId, "token" to "T2"), server.takeRequest().form())

        // T2 stays cached: the next link needs no login
        runBlocking { hoster.resolve(fileUrl, account) }
        assertEquals(2, api.logins)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun sperreBei403LoestKeinenNeuenLoginAus() = withServer { server, hoster ->
        val api = ApiDispatcher(
            loginReply = ::loginReply,
            download = { error(403, "Denied by IP") },
            fileInfo = fileInfo
        )
        server.dispatcher = api
        runBlocking { hoster.checkAccount(account) }

        val fehler = failure { hoster.resolve(fileUrl, account) }
        assertTrue("$fehler", fehler is HosterException)
        assertFalse((fehler as HosterException).permanent)
        assertEquals(Texts.t("hoster_rapidgator_api_error", "Denied by IP"), fehler.message)
        assertEquals(1, api.logins)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun frischesTokenMit401WirdOhneLoginSchleifeDurchgereicht() = withServer { server, hoster ->
        val api = ApiDispatcher(
            loginReply = ::loginReply,
            download = { error(401, "Session not exist") },
            fileInfo = fileInfo
        )
        server.dispatcher = api

        val fehler = failure { hoster.resolve(fileUrl, account) }
        assertTrue("$fehler", fehler is HosterException && !fehler.permanent)
        assertEquals(1, api.logins)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun kontopruefungMeldetSichBeiAbgelaufenemTokenNeuAn() = withServer { server, hoster ->
        val api = ApiDispatcher(
            loginReply = ::loginReply,
            download = { token ->
                if (token == "T2") json("""{"status":200,"details":null,"response":{"download_url":"https://pr5.rapidgator.net/x/archiv.part1.rar"}}""")
                else error(401, "Session not exist")
            },
            userInfo = { token ->
                if (token == "T1") error(401, "Session not exist")
                else json("""{"status":200,"details":null,"response":{"user":{"is_premium":false,"traffic":{"left":null,"total":null}}}}""")
            },
            fileInfo = fileInfo
        )
        server.dispatcher = api
        runBlocking { hoster.checkAccount(account) }
        assertEquals(1, server.requestCount)

        // user/info with T1 is rejected: one re-login, the login's user object counts
        val info = runBlocking { hoster.checkAccount(account) }
        assertEquals("Premium", info.statusText)
        assertEquals(2, api.logins)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        assertEquals(mapOf("token" to "T1"), server.takeRequest().form())
        assertEquals("/api/user/login", server.takeRequest().path)

        // T2 is cached afterwards: user/info succeeds without a login
        val frei = runBlocking { hoster.checkAccount(account) }
        assertEquals(hoster.freeStatusText, frei.statusText)
        assertEquals(-1L, frei.trafficLeft)
        assertEquals(2, api.logins)
        assertEquals(4, server.requestCount)
        assertEquals(mapOf("token" to "T2"), server.takeRequest().form())

        val link = runBlocking { hoster.resolve(fileUrl, account) }
        assertEquals("https://pr5.rapidgator.net/x/archiv.part1.rar", link.directUrl)
        assertEquals(2, api.logins)
    }

    @Test
    fun falschesPasswortIstDauerhaft() = withServer { server, hoster ->
        server.dispatcher = ApiDispatcher(
            loginReply = { error(401, "Login or password is wrong") },
            download = { MockResponse().setResponseCode(500) },
            fileInfo = fileInfo
        )
        val fehler = failure { hoster.checkAccount(account) }
        assertTrue("$fehler", fehler is HosterException && fehler.permanent)
        assertEquals(Texts.t("hoster_rapidgator_api_error", "Login or password is wrong"), fehler!!.message)
        assertNull(runBlocking { hoster.checkLink(fileUrl, account) }.online)
        assertEquals(2, server.requestCount)
    }
}
