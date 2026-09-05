package com.jdandroid

import com.jdandroid.hoster.HosterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HosterMatchTest {

    private fun idFor(url: String) = HosterRegistry.forUrl(url)?.id

    @Test
    fun rapidgatorVarianten() {
        assertEquals("rapidgator", idFor("https://rapidgator.net/file/abc123def456/name.rar.html"))
        assertEquals("rapidgator", idFor("https://rg.to/file/deadbeef00/x.zip"))
        assertEquals("rapidgator", idFor("http://www.rapidgator.net/file/abc123/y"))
    }

    @Test
    fun oneFichierVarianten() {
        assertEquals("onefichier", idFor("https://1fichier.com/?abc123"))
        assertEquals("onefichier", idFor("https://www.1fichier.com/?xY9zAb"))
        assertEquals("onefichier", idFor("https://1fichier.com/?k7x2m9p4q1w8e5r3t6y0"))
        assertEquals("onefichier", idFor("https://1fichier.com/?k7x2m9p4q1w8e5r3t6y0&af=1234"))
    }

    @Test
    fun oneFichierAliasDomains() {
        for (domain in listOf(
            "alterupload.com", "cjoint.net", "desfichiers.com", "dfichiers.com", "megadl.fr",
            "mesfichiers.org", "piecejointe.net", "pjointe.com", "tenvoi.com", "dl4free.com"
        )) {
            assertEquals(domain, "onefichier", idFor("https://$domain/?abcde12345"))
            assertEquals(domain, "onefichier", idFor("http://www.$domain/?abcde12345"))
        }
    }

    @Test
    fun oneFichierOhneIdNichtErkannt() {
        assertNull(idFor("https://1fichier.com/"))
        assertNull(idFor("https://1fichier.com/?abc"))
        assertNull(idFor("https://1fichier.com/register.pl"))
    }

    @Test
    fun ddownloadVarianten() {
        assertEquals("ddownload", idFor("https://ddownload.com/a1b2c3d4e5f6"))
        assertEquals("ddownload", idFor("https://ddownload.com/f/a1b2c3d4e5f6"))
        assertEquals("ddownload", idFor("https://ddownload.com/d/a1b2c3d4e5f6/name.rar"))
        assertEquals("ddownload", idFor("https://www.ddownload.com/chnaz5epeg4t/scn-smps8.rar.html"))
        assertEquals("ddownload", idFor("https://ddl.to/a1b2c3d4e5f6"))
    }

    /** Page paths like /pricing or /register.html are not file codes. */
    @Test
    fun ddownloadSeitenpfadeNichtErkannt() {
        assertNull(idFor("https://ddownload.com/pricing"))
        assertNull(idFor("https://ddownload.com/register.html"))
        assertNull(idFor("https://ddownload.com/login.html"))
        assertNull(idFor("https://ddownload.com/?op=my_account"))
        // 10 or 13 characters are not a 12-character code
        assertNull(idFor("https://ddownload.com/a1b2c3d4e5"))
        assertNull(idFor("https://ddownload.com/a1b2c3d4e5f6g"))
    }

    @Test
    fun fremdeHosterNichtErkannt() {
        assertNull(idFor("https://mega.nz/file/abc"))
        assertNull(idFor("https://example.com/file/abc123"))
    }
}
