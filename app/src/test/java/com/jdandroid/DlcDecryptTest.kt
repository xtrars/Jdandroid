package com.jdandroid

import com.jdandroid.container.ContainerDecrypter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Regressionstests fuer die DLC-Entschluesselung, offline. Der rc-Wert stammt
 * aus einer echten Antwort des JDownloader-Dienstes; er verraet nichts ueber
 * den Inhalt des Containers.
 */
class DlcDecryptTest {

    /**
     * Der Fehler, der "DLC enthaelt keine lesbaren Links" ausloeste: ein
     * falscher statischer IV. Weil ein einzelner CBC-Block den IV nur per XOR
     * einrechnet, ergab der falsche IV trotzdem ASCII-aehnliche Bytes
     * ("vyIZrRCdZoIIh-WU") und taeuschte einen korrekten Schluessel vor.
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
        // wie nach AES/NoPadding: Nullbytes und 0x10-Padding am Ende
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
