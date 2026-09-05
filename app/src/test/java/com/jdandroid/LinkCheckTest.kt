package com.jdandroid

import com.jdandroid.hoster.DdownloadHoster
import com.jdandroid.hoster.OneFichierHoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of hoster answers for the online check in the link grabber. */
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
        // Actual check_links.pl format: three fields, no status
        val info = fichier.parseCheckLine("https://1fichier.com/?abcdefghij;Film.mkv;734003200")
        assertEquals(true, info.online)
        assertEquals("Film.mkv", info.fileName)
        assertEquals(734003200L, info.fileSize)
        // Four fields with OK are readable too
        assertEquals(true, fichier.parseCheckLine("https://1fichier.com/?abcdefghij;Film.mkv;734003200;OK").online)
    }

    @Test
    fun fichierBadLinkMitZweiFeldernIstOffline() {
        assertEquals(false, fichier.parseCheckLine("https://1fichier.com/?abcdefghij;BAD LINK").online)
        assertEquals(null, fichier.parseCheckLine("kaputt").online)
    }

    @Test
    fun ddownloadGroesseNichtAusWerbung() {
        val html = """<div class="ad">Premium: 200 GB traffic per day</div>
            <h1 class="dk-dl-name">x.rar</h1><span>1.2 GB</span>"""
        val size = ddl.pageFileSize(html)
        assertTrue("Groesse $size", size > 1_200_000_000L && size < 1_300_000_000L)
    }

    @Test
    fun ddownloadApostrophImDateinamen() {
        assertEquals(
            "It's.a.file.mkv",
            ddl.pageFileName("""<input type="hidden" name="fname" value="It's.a.file.mkv">""")
        )
        assertEquals("It's", ddl.hiddenInputs("""<input name="fname" value="It's">""")["fname"])
    }

    /**
     * Actual ddownload.com layout: the title replaces dots with spaces and is
     * preceded by an ad heading; the real name is in h2.dk-dl-name.
     */
    private val echteDateiseite = """<html><head><title>Download scn smps8 S37E02 rar</title></head>
        <body>
        <h2 class="dk-promo-title">Ultimate-Vorteile</h2>
        <div class="dk-dl-header">
          <h2 class="dk-dl-name" title="scn-smps8-S37E02.rar">scn-smps8-S37E02.rar</h2>
          <span class="dk-dl-size">1.46 GB</span>
        </div>
        </body></html>"""

    @Test
    fun ddownloadSeiteLiefertNameUndGroesse() {
        assertEquals("scn-smps8-S37E02.rar", ddl.pageFileName(echteDateiseite))
        val size = ddl.pageFileSize(echteDateiseite)
        assertTrue("Groesse $size", size > 1_500_000_000L && size < 1_600_000_000L)
    }

    @Test
    fun ddownloadNameAusH1() {
        val html = """<h1 class="x dk-dl-name y">Serie.S01E01.mkv</h1>"""
        assertEquals("Serie.S01E01.mkv", ddl.pageFileName(html))
    }

    @Test
    fun ddownloadFnameFeldVorTitel() {
        val html = """<html><head><title>Download Serie S01E01 mkv</title></head>
            <body><input type="hidden" name="fname" value="Serie.S01E01.mkv"></body></html>"""
        assertEquals("Serie.S01E01.mkv", ddl.pageFileName(html))
    }

    /**
     * Title only as a last resort, "Download " stripped; dots replaced by
     * spaces are restored so archives are recognised. Unknown extension: no name.
     */
    @Test
    fun ddownloadTitelNurMitEndung() {
        assertEquals(
            "Serie.S01E01.mkv",
            ddl.pageFileName("<html><head><title>Download Serie.S01E01.mkv - ddownload</title></head></html>")
        )
        assertEquals(
            "scn.smps8.S37E02.rar",
            ddl.pageFileName("<html><head><title>Download scn smps8 S37E02 rar</title></head></html>")
        )
        assertNull(ddl.pageFileName("<html><head><title>Download irgendwas ohne Endung</title></head></html>"))
    }

    @Test
    fun ddownloadOhneNameLiefertNull() {
        assertNull(ddl.pageFileName("<html><title>ddownload.com</title></html>"))
        assertEquals(-1L, ddl.pageFileSize("<html>nix</html>"))
        assertFalse(ddl.pageFileName("<html><title>ddownload.com</title></html>") != null)
    }
}
