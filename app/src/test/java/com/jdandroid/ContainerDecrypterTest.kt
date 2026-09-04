package com.jdandroid

import com.jdandroid.container.ContainerDecrypter
import com.jdandroid.container.ContainerDecrypter.ContainerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Prueft ContainerDecrypter.decryptClickNLoad direkt (JVM, java.util.Base64):
 * Schluessel aus dem jk-Parameter, Formular-Dekodierung und Nachbearbeitung
 * des Klartexts. Verschluesselt wird hier nur, um Testdaten zu erzeugen.
 */
class ContainerDecrypterTest {

    private val key = "1234567890123456".toByteArray(Charsets.US_ASCII)
    private val keyHex = "31323334353637383930313233343536"

    /** AES-128-CBC wie der Browser: Schluessel = IV, mit Nullbytes auf Blockgroesse aufgefuellt. */
    private fun encrypt(plain: String, key: ByteArray = this.key): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val padded = bytes.copyOf(((bytes.size + 15) / 16) * 16)
        val enc = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        }.doFinal(padded)
        return Base64.getEncoder().encodeToString(enc)
    }

    @Test
    fun schluesselAusEinfacherJkFunktion() {
        val crypted = encrypt("https://rapidgator.net/file/a/x.rar\nhttps://1fichier.com/?b")
        val links = ContainerDecrypter.decryptClickNLoad(crypted, "function f(){ return '$keyHex';}")
        assertEquals(
            listOf("https://rapidgator.net/file/a/x.rar", "https://1fichier.com/?b"),
            links
        )
    }

    @Test
    fun zusammengesetzteLiteraleWerdenVerkettet() {
        val jk = "function f(){ return '31323334' + \"35363738\" + '39303132' + '33343536';}"
        val crypted = encrypt("https://1fichier.com/?b")
        assertEquals(listOf("https://1fichier.com/?b"), ContainerDecrypter.decryptClickNLoad(crypted, jk))
    }

    @Test
    fun erstesLiteralMit32HexZeichenGewinnt() {
        // Ein weiteres 32-stelliges Literal (falscher Schluessel) danach darf nicht gewinnen
        val jk = "function f(){ var k='$keyHex'; var x='00000000000000000000000000000000'; return k;}"
        val crypted = encrypt("https://1fichier.com/?b")
        assertEquals(listOf("https://1fichier.com/?b"), ContainerDecrypter.decryptClickNLoad(crypted, jk))
    }

    @Test
    fun schluesselMit17ByteWirdAbgelehnt() {
        val crypted = encrypt("https://1fichier.com/?b")
        val e = assertThrows(ContainerException::class.java) {
            ContainerDecrypter.decryptClickNLoad(crypted, "function f(){ return '${keyHex}ab';}")
        }
        assertTrue(e.message!!, e.message!!.contains("17 Byte"))
    }

    @Test
    fun fehlenderSchluesselErgibtKlareMeldung() {
        val crypted = encrypt("https://1fichier.com/?b")
        assertThrows(ContainerException::class.java) {
            ContainerDecrypter.decryptClickNLoad(crypted, "function f(){ return key();}")
        }
    }

    @Test
    fun leerzeichenImFormularWerdenZuPlus() {
        // Base64 mit "+" erzwingen: so lange probieren, bis eines drin ist
        var n = 0
        var crypted: String
        do {
            crypted = encrypt("https://1fichier.com/?b${n++}")
        } while (!crypted.contains('+'))
        val links = ContainerDecrypter.decryptClickNLoad(crypted.replace('+', ' '), "'$keyHex'")
        assertEquals(listOf("https://1fichier.com/?b${n - 1}"), links)
    }

    @Test
    fun nullbytePaddingWirdEntfernt() {
        // 23 Zeichen -> 9 Nullbytes am Ende des Blocks
        val url = "https://1fichier.com/?b"
        val links = ContainerDecrypter.decryptClickNLoad(encrypt(url), "'$keyHex'")
        assertEquals(listOf(url), links)
        assertEquals(url.length, links[0].length)
    }

    @Test
    fun crlfTrenntLinksUndLeerzeilenFallenWeg() {
        val crypted = encrypt("https://a.example/1\r\n\r\nhttps://b.example/2\r\nkein-link\r\n")
        assertEquals(
            listOf("https://a.example/1", "https://b.example/2"),
            ContainerDecrypter.decryptClickNLoad(crypted, "'$keyHex'")
        )
    }

    @Test
    fun beschaedigteDatenErgebenContainerException() {
        assertThrows(ContainerException::class.java) {
            ContainerDecrypter.decryptClickNLoad("YWJj", "'$keyHex'")
        }
    }
}
