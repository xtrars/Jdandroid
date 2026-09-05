package com.jdandroid

import com.jdandroid.container.ContainerDecrypter
import com.jdandroid.container.ContainerDecrypter.ContainerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline DLC decryption. The rc value comes from a real JDownloader service
 * response and reveals nothing about the container content.
 */
class DlcDecryptTest {

    /**
     * Guards the static IV: a single CBC block only XORs the IV in, so a wrong
     * IV still yields ASCII-like bytes and mimics a correct key.
     */
    @Test
    fun containerSchluesselWirdKorrektAbgeleitet() {
        val key = ContainerDecrypter.deriveKey("tXQhqHC104UmeIpi3zvY0w==")
        assertEquals("vyIZrUCnZbIIipWS", String(key, Charsets.US_ASCII))
    }

    @Test
    fun auffuellbytesNachAesWerdenIgnoriert() {
        val xml = "<dlc><content></content></dlc>"
        val b64 = Base64.getEncoder().encodeToString(xml.toByteArray())
        // As after AES/NoPadding: zero bytes and 0x10 padding at the end
        val padded = b64.toByteArray() + ByteArray(12) + ByteArray(16) { 0x10 }
        assertEquals(xml, ContainerDecrypter.decodeXml(padded))
    }

    @Test
    fun paketeMitNamenUndUrlsWerdenGelesen() {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        val xml = """
            <dlc><header></header><content>
              <package name="${b64("Serie S01")}">
                <file><url>${b64("https://rapidgator.net/file/aaa/e01.rar")}</url><filename>${b64("e01.rar")}</filename></file>
                <file><url>${b64("https://ddownload.com/bbbbbbbbbb/e02.rar")}</url></file>
              </package>
              <package name="${b64("Film")}">
                <file><url>${b64("https://1fichier.com/?ccc")}</url></file>
              </package>
            </content></dlc>
        """.trimIndent()
        val packages = ContainerDecrypter.parsePackages(xml)
        assertEquals(2, packages.size)
        assertEquals("Serie S01", packages[0].name)
        assertEquals(2, packages[0].urls.size)
        assertEquals("https://rapidgator.net/file/aaa/e01.rar", packages[0].urls[0])
        assertEquals("Film", packages[1].name)
        assertEquals("https://1fichier.com/?ccc", packages[1].urls[0])
    }

    @Test
    fun dlcOhnePaketstrukturErgibtEinPaketOhneNamen() {
        val url = Base64.getEncoder().encodeToString("https://1fichier.com/?x".toByteArray())
        val packages = ContainerDecrypter.parsePackages("<dlc><url>$url</url></dlc>")
        assertEquals(1, packages.size)
        assertNull(packages[0].name)
        assertEquals("https://1fichier.com/?x", packages[0].urls[0])
    }

    // Service reply (rc) and the container key it decrypts to, see above
    private val rc = "tXQhqHC104UmeIpi3zvY0w=="
    private val realKey = "vyIZrUCnZbIIipWS".toByteArray(Charsets.US_ASCII)
    private val dlcKey = "k".repeat(88)

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    /** Builds a DLC like JDownloader: base64 XML, AES-CBC with the container key, then the 88-character key part. */
    private fun dlc(xml: String, key: ByteArray = realKey): String {
        val payload = b64(xml).toByteArray(Charsets.US_ASCII)
        val padded = payload.copyOf(((payload.size + 15) / 16) * 16)
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        }.doFinal(padded)
        return Base64.getEncoder().encodeToString(encrypted) + dlcKey
    }

    private fun xmlFor(code: String) = """
        <dlc><content>
          <package name="${b64("Serie")}">
            <file><url>${b64("https://rapidgator.net/file/$code/e01.rar")}</url></file>
            <file><url>${b64("https://rapidgator.net/file/bbb/e02.rar")}</url></file>
          </package>
          <package name="${b64("Leer")}"></package>
        </content></dlc>
    """.trimIndent()

    private val xml = xmlFor("aaa")

    @Test
    fun dlcWirdOhneNetzEntschluesseltUndLeerePaketeFallenWeg() {
        val asked = ArrayList<String>()
        val packages = ContainerDecrypter.decryptDlcPackages(dlc(xml)) { asked += it; rc }
        // The service is asked exactly once with the key part of the container
        assertEquals(listOf(dlcKey), asked)
        assertEquals(1, packages.size)
        assertEquals("Serie", packages[0].name)
        assertEquals(
            listOf("https://rapidgator.net/file/aaa/e01.rar", "https://rapidgator.net/file/bbb/e02.rar"),
            packages[0].urls
        )
        assertEquals(packages[0].urls, ContainerDecrypter.decryptDlc(dlc(xml)) { rc })
    }

    @Test
    fun leerzeichenAusDemFormularWerdenZuPlus() {
        // Retry until the ciphertext contains "+", then send it like a form (decoded to spaces)
        var n = 0
        var content: String
        do {
            content = dlc(xmlFor("a${n++}"))
        } while (!content.contains('+'))
        val packages = ContainerDecrypter.decryptDlcPackages(content.replace('+', ' ')) { rc }
        assertEquals("https://rapidgator.net/file/a${n - 1}/e01.rar", packages[0].urls[0])
    }

    @Test
    fun zuKurzerContainerWirdVorDemDienstAbgelehnt() {
        val e = assertThrows(ContainerException::class.java) {
            ContainerDecrypter.decryptDlcPackages("QUJDREVGRw==") { error("Dienst darf nicht gefragt werden") }
        }
        assertTrue(e.message!!, e.message!!.contains("zu kurz"))
    }

    @Test
    fun datenAusserhalbDerBlockgroesseWerdenVorDemDienstAbgelehnt() {
        // 5 bytes of data: not a multiple of the AES block size
        val e = assertThrows(ContainerException::class.java) {
            ContainerDecrypter.decryptDlcPackages(b64("abcde") + dlcKey) { error("Dienst darf nicht gefragt werden") }
        }
        assertTrue(e.message!!, e.message!!.contains("beschädigt"))
    }

    @Test
    fun containerOhneLinksErgibtKlareMeldung() {
        val empty = """<dlc><content><package name="${b64("Leer")}"><file><filename>${b64("x")}</filename></file></package></content></dlc>"""
        val e = assertThrows(ContainerException::class.java) {
            ContainerDecrypter.decryptDlcPackages(dlc(empty)) { rc }
        }
        assertTrue(e.message!!, e.message!!.contains("keine lesbaren Links"))
    }

    @Test
    fun dienstfehlerWirdDurchgereicht() {
        val e = assertThrows(ContainerException::class.java) {
            ContainerDecrypter.decryptDlcPackages(dlc(xml)) { throw ContainerException("Dienst weg") }
        }
        assertEquals("Dienst weg", e.message)
    }
}
