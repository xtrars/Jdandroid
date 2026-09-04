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

    /**
     * Tatsaechlicher Seitenaufbau von ddownload.com: der Titel ersetzt Punkte
     * durch Leerzeichen, vor dem Dateinamen steht eine Werbe-Ueberschrift,
     * der echte Name steht in h2.dk-dl-name.
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

    /** Titel nur als letzter Ausweg und nur mit Dateiendung, "Download " abgeschnitten. */
    @Test
    fun ddownloadTitelNurMitEndung() {
        assertEquals(
            "Serie.S01E01.mkv",
            ddl.pageFileName("<html><head><title>Download Serie.S01E01.mkv - ddownload</title></head></html>")
        )
        assertNull(ddl.pageFileName("<html><head><title>Download scn smps8 S37E02 rar</title></head></html>"))
    }

    @Test
    fun ddownloadOhneNameLiefertNull() {
        assertNull(ddl.pageFileName("<html><title>ddownload.com</title></html>"))
        assertEquals(-1L, ddl.pageFileSize("<html>nix</html>"))
        assertFalse(ddl.pageFileName("<html><title>ddownload.com</title></html>") != null)
    }
}
