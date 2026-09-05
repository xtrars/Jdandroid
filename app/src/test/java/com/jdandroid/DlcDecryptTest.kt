package com.jdandroid

import com.jdandroid.container.ContainerDecrypter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

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
}
