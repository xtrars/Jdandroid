package com.jdandroid

import com.jdandroid.hoster.DdownloadHoster
import com.jdandroid.hoster.OneFichierHoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auswertung der Hoster-Antworten fuer die Online-Pruefung im Linksammler. */
class LinkCheckTest {

    private val fichier = OneFichierHoster()
    private val ddl = DdownloadHoster()

    @Test
    fun fichierNotFoundIstOffline() {
        val info = fichier.parseCheckLine("https://1fichier.com/?abcdefghij;;;NOT FOUND")
        assertEquals(false, info.online)
        assertNull(info.fileName)
    }

    @Test
    fun fichierTrefferLiefertNameUndGroesse() {
        val info = fichier.parseCheckLine("https://1fichier.com/?abcdefghij;Film.mkv;734003200;OK")
        assertEquals(true, info.online)
        assertEquals("Film.mkv", info.fileName)
        assertEquals(734003200L, info.fileSize)
    }

    @Test
    fun ddownloadSeiteLiefertNameUndGroesse() {
        val html = """<html><head><title>Download Serie.S01E01.mkv - ddownload</title></head>
            <body><input type="hidden" name="fname" value="Serie.S01E01.mkv">
            <span class="size">1.46 GB</span></body></html>"""
        assertEquals("Serie.S01E01.mkv", ddl.pageFileName(html))
        val size = ddl.pageFileSize(html)
        assertTrue("Groesse $size", size > 1_500_000_000L && size < 1_600_000_000L)
    }

    @Test
    fun ddownloadOhneNameLiefertNull() {
        assertNull(ddl.pageFileName("<html><title>ddownload.com</title></html>"))
        assertEquals(-1L, ddl.pageFileSize("<html>nix</html>"))
        assertFalse(ddl.pageFileName("<html><title>ddownload.com</title></html>") != null)
    }
}
