package com.jdandroid.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [ContainerFiles.looksLikeDlc] decides alone whether an opened file is
 * decrypted as DLC or rejected as "not a DLC file".
 */
class ContainerFilesTest {

    @Test
    fun base64MitSchluesselteilZaehltAlsDlc() {
        assertTrue(ContainerFiles.looksLikeDlc("A".repeat(86) + "=="))
        // Real structure: base64 payload followed by the 64-byte key part (88 characters, ends with "==")
        val payload = Base64.getEncoder().encodeToString("<dlc><content></content></dlc>".toByteArray())
        val keyPart = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        assertTrue(keyPart.endsWith("=="))
        assertTrue(ContainerFiles.looksLikeDlc(payload + keyPart))
    }

    @Test
    fun zeilenumbruecheWerdenIgnoriert() {
        assertTrue(ContainerFiles.looksLikeDlc(("A".repeat(40) + "\r\n").repeat(3) + "==\n"))
    }

    @Test
    fun zuKurzOderOhneSchluesselendeIstKeineDlc() {
        assertFalse(ContainerFiles.looksLikeDlc("A".repeat(85) + "=="))
        assertFalse(ContainerFiles.looksLikeDlc("A".repeat(88)))
        assertFalse(ContainerFiles.looksLikeDlc(""))
    }

    @Test
    fun linklisteIstKeineDlc() {
        assertFalse(ContainerFiles.looksLikeDlc("https://rapidgator.net/file/abc/x.rar\n".repeat(5) + "=="))
        assertFalse(ContainerFiles.looksLikeDlc("https://1fichier.com/?abc\n".repeat(10)))
    }
}
