package com.jdandroid

import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlRequest
import com.jdandroid.container.CnlStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end test of the Click'n'Load server: starts it like the app and
 * sends exactly the requests a browser page with a CnL button sends.
 */
class ClickNLoadServerTest {

    private lateinit var server: ClickNLoadServer
    // Written by the server thread, read by the test thread
    private val received = CopyOnWriteArrayList<CnlRequest>()
    private var port = 0

    @Before
    fun start() {
        // Port 0 = free port, so the test does not collide with a running app
        // or a parallel test run
        server = ClickNLoadServer(port = 0) { request -> received.add(request) }
        server.start()
        port = server.listeningPort
    }

    @After
    fun stop() {
        server.stop()
    }

    @Test
    fun testserverBelegtFreienPortStatt9666() {
        assertTrue("Port muss vergeben sein", port > 0)
        assertTrue("Port darf nicht der feste App-Port sein", port != ClickNLoadServer.PORT)
        // A second server (parallel test run) must start concurrently
        val second = ClickNLoadServer(port = 0) { }
        try {
            second.start()
            assertTrue(second.listeningPort > 0)
            assertTrue(second.listeningPort != port)
            assertEquals("Server antwortet (HTTP 200).", ClickNLoadServer.selfTest(second.listeningPort))
        } finally {
            second.stop()
        }
    }

    @Test
    fun stopBeendetDenServer() {
        assertTrue(server.isAlive)
        server.stop()
        assertTrue(!server.isAlive)
        assertTrue(ClickNLoadServer.selfTest(port).startsWith("Server nicht erreichbar"))
    }

    @Test
    fun selbsttestErreichtLaufendenServer() {
        assertEquals("Server antwortet (HTTP 200).", ClickNLoadServer.selfTest(port))
    }

    @Test
    fun selbsttestMeldetFehlendenServer() {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(ClickNLoadServer.selfTest(closed).startsWith("Server nicht erreichbar"))
    }

    private class Reply(val code: Int, val body: String, val headers: Map<String, List<String>>)

    private fun request(
        path: String,
        body: String? = null,
        method: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Reply {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (method != null) conn.requestMethod = method
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        val contentType = conn.contentType.orEmpty()
        val responseHeaders = conn.headerFields.filterKeys { it != null }
            .mapKeys { it.key.lowercase() }
        conn.disconnect()
        return Reply(code, "$contentType|$text", responseHeaders)
    }

    /** Sends raw bytes to the server and returns the complete response. */
    private fun raw(request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readText()
    }

    private val key = "1234567890123456".toByteArray()
    private val jk = "function f(){ return '31323334353637383930313233343536';}"

    private fun encrypt(links: String): String {
        val padded = links.toByteArray().let { it.copyOf(((it.size + 15) / 16) * 16) }
        return Base64.getEncoder().encodeToString(
            Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            }.doFinal(padded)
        )
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test
    fun jdcheckWirdAlsJavascriptAusgeliefert() {
        val reply = request("/jdcheck.js")
        assertEquals(200, reply.code)
        // Browsers do not execute text/html as script; the page would not see the app
        assertTrue("MIME muss JavaScript sein, war: ${reply.body}", reply.body.contains("javascript"))
        assertTrue(reply.body.contains("jdownloader=true"))
    }

    @Test
    fun verschluesselteLinksWerdenUebernommen() {
        val links = "https://rapidgator.net/file/aaa111/x.rar\n" +
            "https://ddownload.com/bbb222ccc3"
        val body = "crypted=${enc(encrypt(links))}&jk=${enc(jk)}"
        val reply = request("/flash/addcrypted2", body)

        assertEquals(200, reply.code)
        assertTrue(reply.body.contains("success"))
        assertEquals(1, received.size)
        assertEquals(
            listOf("https://rapidgator.net/file/aaa111/x.rar", "https://ddownload.com/bbb222ccc3"),
            received[0].urls
        )
    }

    @Test
    fun paketnameUndPasswoerterWerdenDurchgereicht() {
        val links = "https://rapidgator.net/file/aaa111/x.part1.rar"
        val body = "crypted=${enc(encrypt(links))}&jk=${enc(jk)}" +
            "&package=${enc("Mein Paket")}&passwords=${enc("geheim1\ngeheim2")}" +
            "&source=${enc("https://www.example.org/release/123")}"
        val reply = request("/flash/addcrypted2", body)

        assertEquals(200, reply.code)
        val req = received.single()
        assertEquals("Mein Paket", req.packageName)
        assertEquals(listOf("geheim1", "geheim2"), req.passwords)
        assertEquals("https://www.example.org/release/123", req.source)
    }

    @Test
    fun rohesPlusImBase64WirdRepariert() {
        // Some pages send "crypted" without URL encoding, so "+" arrives as a
        // space. First produce a Base64 containing "+" ...
        var links = "https://rapidgator.net/file/aaa111/x.rar"
        var crypted = encrypt(links)
        var salt = 0
        while (!crypted.contains('+')) {
            links = "https://rapidgator.net/file/aaa111/x${salt++}.rar"
            crypted = encrypt(links)
        }
        // ... then send it unencoded (only jk encoded, it contains no "+")
        val body = "crypted=$crypted&jk=${enc(jk)}"
        val reply = request("/flash/addcrypted2", body)

        assertEquals("Antwort: ${reply.body}", 200, reply.code)
        assertEquals(listOf(links), received.single().urls)
    }

    @Test
    fun unverschluesselteLinksWerdenUebernommen() {
        val urls = "https://1fichier.com/?abc123\nhttps://rg.to/file/dd44/y.zip"
        val reply = request("/flash/add", "urls=${enc(urls)}")
        assertEquals(200, reply.code)
        assertEquals(2, received.single().urls.size)
    }

    @Test
    fun leereAnfrageMeldetFailed() {
        // The page should show "failed" instead of success
        val reply = request("/flash/add", "urls=")
        assertEquals(400, reply.code)
        assertTrue(reply.body.contains("failed"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun ungueltigerSchluesselMeldetFailedStattAbsturz() {
        val badJk = "function f(){ return '3132333435363738393031323334353637';}" // 17 Byte
        val reply = request("/flash/addcrypted2", "crypted=${enc(encrypt("https://x"))}&jk=${enc(badJk)}")
        assertEquals(400, reply.code)
        assertTrue(received.isEmpty())
    }

    @Test
    fun preflightLiefertPrivateNetworkHeader() {
        // Chrome "Local Network Access": OPTIONS must answer 204 with these headers
        val reply = request(
            "/flash/addcrypted2", method = "OPTIONS",
            headers = mapOf(
                "Origin" to "https://example.org",
                "Access-Control-Request-Method" to "POST",
                "Access-Control-Request-Private-Network" to "true"
            )
        )
        assertEquals(204, reply.code)
        assertEquals(listOf("true"), reply.headers["access-control-allow-private-network"])
        assertEquals(listOf("true"), reply.headers["access-control-allow-local-network"])
        // HttpURLConnection suppresses the Origin header (restricted header),
        // so only check that the server allows some origin at all
        assertNotNull(reply.headers["access-control-allow-origin"])
        assertNotNull(reply.headers["access-control-allow-methods"])
        assertTrue(received.isEmpty())
    }

    @Test
    fun preflightSpiegeltOriginUndAngefragteHeader() {
        // Raw request because HttpURLConnection suppresses the Origin header
        val answer = raw(
            "OPTIONS /flash/addcrypted2 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Origin: https://example.org\r\n" +
                "Access-Control-Request-Method: POST\r\n" +
                "Access-Control-Request-Headers: content-type, x-custom\r\n" +
                "Access-Control-Request-Private-Network: true\r\n\r\n"
        )
        val lines = answer.split("\r\n")
        assertEquals("HTTP/1.1 204 No Content", lines[0])
        val headers = lines.drop(1).takeWhile { it.isNotEmpty() }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        assertEquals("https://example.org", headers["access-control-allow-origin"])
        assertEquals("GET, POST, OPTIONS", headers["access-control-allow-methods"])
        assertEquals("content-type, x-custom", headers["access-control-allow-headers"])
        assertEquals("true", headers["access-control-allow-private-network"])
        assertEquals("true", headers["access-control-allow-local-network"])
        assertEquals("86400", headers["access-control-max-age"])
        assertEquals("close", headers["connection"])
        assertEquals("0", headers["content-length"])
    }

    @Test
    fun ohneOriginBleibtAllowOriginStern() {
        val reply = request("/jdcheck.js")
        assertEquals(listOf("*"), reply.headers["access-control-allow-origin"])
        assertEquals(listOf("Content-Type, X-Requested-With"), reply.headers["access-control-allow-headers"])
    }

    @Test
    fun crossdomainXmlWirdAusgeliefert() {
        val reply = request("/crossdomain.xml")
        assertEquals(200, reply.code)
        assertTrue(reply.body.startsWith("application/xml|"))
        assertTrue(reply.body.contains("<cross-domain-policy>"))
    }

    @Test
    fun wurzelAntwortetMitLebenszeichen() {
        val reply = request("/")
        assertEquals(200, reply.code)
        assertTrue(reply.body.endsWith("|JDAndroid"))
    }

    @Test
    fun unbekannterPfadLiefert404() {
        val reply = request("/gibt/es/nicht")
        assertEquals(404, reply.code)
        assertTrue(reply.body.contains("not found"))
        assertTrue(received.isEmpty())
        // The error carries CORS headers too
        assertNotNull(reply.headers["access-control-allow-origin"])
    }

    @Test
    fun fehlerhafteRequestLineLiefert400() {
        val answer = raw("KEIN HTTP\r\n\r\n")
        assertTrue("Antwort: $answer", answer.startsWith("HTTP/1.1 400 Bad Request"))
        assertTrue(answer.contains("bad request"))
    }

    @Test
    fun ueberlangeHeaderWerdenAbgelehnt() {
        val answer = raw(
            "GET /jdcheck.js HTTP/1.1\r\n" +
                "X-Fill: " + "a".repeat(9000) + "\r\n\r\n"
        )
        assertTrue("Antwort: $answer", answer.startsWith("HTTP/1.1 431"))
    }

    @Test
    fun mehrereAnfragenParallel() {
        // Thread pool: concurrent clients all get an answer. Results are
        // collected and checked on the test thread; a failure inside a
        // client thread would otherwise only print a stack trace.
        val codes = ConcurrentLinkedQueue<Int>()
        val threads = (1..8).map {
            Thread { codes += runCatching { request("/jdcheck.js").code }.getOrDefault(-1) }.apply { start() }
        }
        threads.forEach { it.join(10000) }
        assertTrue(threads.none { it.isAlive })
        assertEquals(List(8) { 200 }, codes.toList())
    }

    @Test
    fun dlcContainerLaeuftUeberDenDlcPfad() {
        // /flash/addcrypted without jk = complete DLC container. A too short
        // container is rejected before any network access; the status line
        // proves the DLC branch (not CnL 2) was taken.
        val reply = request("/flash/addcrypted", "crypted=${enc("QUJDREVGRw==")}")
        assertEquals(400, reply.code)
        assertTrue(reply.body.contains("failed"))
        assertTrue(received.isEmpty())
        val status = CnlStatus.lastRequest.value.orEmpty()
        assertTrue("Statuszeile: $status", status.contains("/flash/addcrypted") && status.contains("DLC"))
    }

    @Test
    fun zuGrosserKoerperWirdMit413Abgelehnt() {
        // The server must not read an announced 3 MiB body at all (OOM
        // protection) but answer 413 immediately.
        val length = 3L * 1024 * 1024
        assertTrue(length > ClickNLoadServer.MAX_BODY_BYTES)
        val statusLine = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(
                ("POST /flash/addcrypted2 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: $length\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readText()
        }
        assertTrue("Antwort: $statusLine", statusLine.startsWith("HTTP/1.1 413"))
        assertTrue(statusLine.contains("failed"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun passwortlisteWirdAufMaximumGekappt() {
        val passwords = (1..60).joinToString("\n") { "pw$it" }
        val body = "crypted=${enc(encrypt("https://rapidgator.net/file/aaa111/x.rar"))}&jk=${enc(jk)}" +
            "&passwords=${enc(passwords)}"
        val reply = request("/flash/addcrypted2", body)

        assertEquals(200, reply.code)
        val req = received.single()
        assertEquals(ClickNLoadServer.MAX_PASSWORDS, req.passwords.size)
        assertEquals((1..ClickNLoadServer.MAX_PASSWORDS).map { "pw$it" }, req.passwords)
    }

    @Test
    fun refererDientAlsQuelleWennSourceFehlt() {
        val body = "crypted=${enc(encrypt("https://rapidgator.net/file/aaa111/x.rar"))}&jk=${enc(jk)}"
        val reply = request(
            "/flash/addcrypted2", body,
            headers = mapOf("Referer" to "https://www.example.org/thread/42")
        )
        assertEquals(200, reply.code)
        assertEquals("https://www.example.org/thread/42", received.single().source)
    }

    @Test
    fun getMitUrlsParameterWirdUebernommen() {
        // Older pages send the links as GET parameters instead of a form
        val urls = "https://1fichier.com/?abc123\nhttps://rg.to/file/dd44/y.zip"
        val reply = request("/flash/add?urls=${enc(urls)}")
        assertEquals(200, reply.code)
        assertTrue(reply.body.contains("success"))
        assertEquals(
            listOf("https://1fichier.com/?abc123", "https://rg.to/file/dd44/y.zip"),
            received.single().urls
        )
    }

    @Test
    fun passwoerterWerdenGekapptUndDedupliziert() {
        val long = "x".repeat(ClickNLoadServer.MAX_PASSWORD_LENGTH + 50)
        val passwords = listOf(long, " geheim ", "geheim", "", "   ", "geheim").joinToString("\n")
        val body = "crypted=${enc(encrypt("https://rapidgator.net/file/aaa111/x.rar"))}&jk=${enc(jk)}" +
            "&passwords=${enc(passwords)}"
        val reply = request("/flash/addcrypted2", body)

        assertEquals(200, reply.code)
        val req = received.single()
        assertEquals(2, req.passwords.size)
        assertEquals(ClickNLoadServer.MAX_PASSWORD_LENGTH, req.passwords[0].length)
        assertEquals("geheim", req.passwords[1])
    }

    @Test
    fun paketnameWirdGekapptUndLeerZuNull() {
        val name = "p".repeat(ClickNLoadServer.MAX_PACKAGE_LENGTH + 1)
        val body = "crypted=${enc(encrypt("https://rapidgator.net/file/aaa111/x.rar"))}&jk=${enc(jk)}"
        request("/flash/addcrypted2", "$body&package=${enc(name)}")
        assertEquals(ClickNLoadServer.MAX_PACKAGE_LENGTH, received.single().packageName!!.length)

        received.clear()
        request("/flash/addcrypted2", "$body&package=${enc("   ")}")
        assertNull(received.single().packageName)
    }

    @Test
    fun getParameterHabenVorrangVorFormularfeldern() {
        val links = "https://rapidgator.net/file/aaa111/x.rar"
        val body = "crypted=${enc(encrypt(links))}&jk=${enc(jk)}&package=${enc("aus dem Formular")}"
        val reply = request("/flash/addcrypted2?package=${enc("aus der URL")}", body)

        assertEquals(200, reply.code)
        assertEquals("aus der URL", received.single().packageName)
        assertEquals(listOf(links), received.single().urls)
    }

    @Test
    fun linklisteWirdAufMaximumGekappt() {
        val links = (1..ClickNLoadServer.MAX_LINKS + 1).joinToString("\n") { "https://rapidgator.net/file/$it/x.rar" }
        val reply = request("/flash/addcrypted2", "crypted=${enc(encrypt(links))}&jk=${enc(jk)}")

        assertEquals(200, reply.code)
        val urls = received.single().urls
        assertEquals(ClickNLoadServer.MAX_LINKS, urls.size)
        assertEquals("https://rapidgator.net/file/1/x.rar", urls.first())
        assertEquals("https://rapidgator.net/file/${ClickNLoadServer.MAX_LINKS}/x.rar", urls.last())
    }

    @Test
    fun flashgotPfadNimmtLinksAn() {
        val reply = request("/flashgot", "urls=${enc("https://1fichier.com/?abc123")}")
        assertEquals(200, reply.code)
        assertEquals(listOf("https://1fichier.com/?abc123"), received.single().urls)
    }

    @Test
    fun postOhneContentLengthLiestKeinenKoerper() {
        // Without Content-Length the body is not read: the form fields are
        // missing, and the status line names the missing fields instead of
        // the server waiting for more bytes.
        val answer = raw(
            "POST /flash/add HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Connection: close\r\n\r\n" +
                "urls=${enc("https://1fichier.com/?abc123")}"
        )
        assertTrue("Antwort: $answer", answer.startsWith("HTTP/1.1 400"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun jedeAntwortSchliesstDieVerbindung() {
        val reply = request("/jdcheck.js")
        assertEquals(listOf("close"), reply.headers["connection"])
        assertEquals(listOf("close"), request("/gibt/es/nicht").headers["connection"])
    }
}
