package com.jdandroid

import com.jdandroid.container.ClickNLoadServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val received = mutableListOf<String>()
    private var port = 0

    @Before
    fun start() {
        server = ClickNLoadServer { links -> received.addAll(links) }
        // Testport statt 9666, damit der Test nicht mit einer laufenden App kollidiert
        server.start(5000, true)
        port = server.listeningPort
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun request(path: String, body: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
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
        conn.disconnect()
        return code to "$contentType|$text"
    }

    @Test
    fun jdcheckWirdAlsJavascriptAusgeliefert() {
        val (code, body) = request("/jdcheck.js")
        assertEquals(200, code)
        // Browser fuehren text/html nicht als Script aus -> Seite saehe die App nicht
        assertTrue("MIME muss JavaScript sein, war: $body", body.contains("javascript"))
        assertTrue(body.contains("jdownloader=true"))
    }

    @Test
    fun verschluesselteLinksWerdenUebernommen() {
        val key = "1234567890123456".toByteArray()
        val links = "https://rapidgator.net/file/aaa111/x.rar\n" +
            "https://ddownload.com/bbb222ccc3"
        val padded = links.toByteArray().let { it.copyOf(((it.size + 15) / 16) * 16) }
        val crypted = Base64.getEncoder().encodeToString(
            Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            }.doFinal(padded)
        )
        val jk = "function f(){ return '31323334353637383930313233343536';}"

        val body = "crypted=${URLEncoder.encode(crypted, "UTF-8")}" +
            "&jk=${URLEncoder.encode(jk, "UTF-8")}"
        val (code, response) = request("/flash/addcrypted2", body)

        assertEquals(200, code)
        assertTrue(response.contains("success"))
        assertEquals(2, received.size)
        assertEquals("https://rapidgator.net/file/aaa111/x.rar", received[0])
        assertEquals("https://ddownload.com/bbb222ccc3", received[1])
    }

    @Test
    fun unverschluesselteLinksWerdenUebernommen() {
        val urls = "https://1fichier.com/?abc123\nhttps://rg.to/file/dd44/y.zip"
        val (code, _) = request("/flash/add", "urls=${URLEncoder.encode(urls, "UTF-8")}")
        assertEquals(200, code)
        assertEquals(2, received.size)
    }
}
