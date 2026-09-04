package com.jdandroid

import com.jdandroid.hoster.OneFichierHoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kanonische 1fichier-URL fuer API-Aufrufe: https://1fichier.com/?<id>, id klein. */
class OneFichierNormalizeTest {

    private val hoster = OneFichierHoster()

    @Test
    fun standardLinkBleibt() {
        assertEquals("https://1fichier.com/?abcde12345", hoster.normalize("https://1fichier.com/?abcde12345"))
    }

    @Test
    fun aliasDomainUndWwwWerdenErsetzt() {
        assertEquals("https://1fichier.com/?abcde12345", hoster.normalize("https://www.1fichier.com/?abcde12345"))
        assertEquals("https://1fichier.com/?abcde12345", hoster.normalize("http://desfichiers.com/?abcde12345"))
        assertEquals("https://1fichier.com/?abcde12345", hoster.normalize("https://www.pjointe.com/?abcde12345"))
    }

    @Test
    fun idWirdKleingeschriebenUndAnhaengselEntfernt() {
        assertEquals("https://1fichier.com/?xy9zabcdef", hoster.normalize("https://1fichier.com/?xY9zAbCdEf&af=1234"))
        assertEquals("https://1fichier.com/?abcde12345", hoster.normalize("https://1fichier.com/?abcde12345/name.rar"))
    }

    @Test
    fun keinLinkLiefertNull() {
        assertNull(hoster.normalize("https://1fichier.com/register.pl"))
        assertNull(hoster.normalize("https://example.com/?abcde12345"))
    }
}
