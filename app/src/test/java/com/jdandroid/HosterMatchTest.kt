package com.jdandroid

import com.jdandroid.hoster.HosterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Prueft die URL-Erkennung (matches) der Hoster-Plugins. */
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
    }

    @Test
    fun ddownloadVarianten() {
        assertEquals("ddownload", idFor("https://ddownload.com/a1b2c3d4e5f6"))
        assertEquals("ddownload", idFor("https://ddownload.com/f/a1b2c3d4e5"))
        assertEquals("ddownload", idFor("https://ddl.to/a1b2c3d4e5f6"))
    }

    @Test
    fun fremdeHosterNichtErkannt() {
        assertNull(idFor("https://mega.nz/file/abc"))
        assertNull(idFor("https://example.com/file/abc123"))
    }
}
