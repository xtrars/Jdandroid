package com.jdandroid

import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End-Test des Click'n'Load-Servers: startet ihn wie in der App und
 * sendet exakt die Anfragen, die eine Browser-Seite mit CnL-Button schickt.
 */
class ClickNLoadServerTest {

    private lateinit var server: ClickNLoadServer
    private val received = mutableListOf<CnlRequest>()
    private var port = 0

    @Before
    fun start() {
        server = ClickNLoadServer { request -> received.add(request) }
        // Testport statt 9666, damit der Test nicht mit einer laufenden App kollidiert
        server.start(5000, true)
        port = server.listeningPort
    }

    @After
    fun stop() {
        server.stop()
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
        // Browser fuehren text/html nicht als Script aus -> Seite saehe die App nicht
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
        // Manche Seiten senden "crypted" ohne URL-Kodierung: "+" kommt dann
        // als Leerzeichen an. Erst ein Base64 mit "+" erzeugen ...
        var links = "https://rapidgator.net/file/aaa111/x.rar"
        var crypted = encrypt(links)
        var salt = 0
        while (!crypted.contains('+')) {
            links = "https://rapidgator.net/file/aaa111/x${salt++}.rar"
            crypted = encrypt(links)
        }
        // ... und es unkodiert schicken (nur jk kodiert, das enthaelt kein "+")
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
        // Wie JDownloader: die Seite soll "fehlgeschlagen" anzeigen statt Erfolg
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
        // Chrome "Local Network Access": OPTIONS muss 204 mit diesen Headern liefern
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
        // HttpURLConnection unterdrueckt den Origin-Header (restricted header),
        // daher hier nur pruefen, dass der Server ueberhaupt einen Origin freigibt
        assertNotNull(reply.headers["access-control-allow-origin"])
        assertNotNull(reply.headers["access-control-allow-methods"])
    }
}
