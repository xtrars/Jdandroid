package com.jdandroid

import com.jdandroid.hoster.DdownloadHoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geprüft gegen den tatsächlichen Seitenaufbau von ddownload.com
 * (abgerufen am 30.08.2026).
 */
class DdownloadFormTest {

    private val hoster = DdownloadHoster()

    private val echteSeite = """
        <html><body>
        <a href="https://ddownload.com/pricing">Upgrade</a>
        <a href="https://ddownload.com/?op=my_account">Konto</a>
        <link href="https://ddownload.com/assets/style.css">
        <script>
          var tracker = "https://ddownload.com/cgi-bin/tracker.cgi?file_code=chnaz5epeg4t";
        </script>
        <form name="F1" method="POST" action="" style="display:contents;">
          <input type="hidden" name="op" value="download2">
          <input type="hidden" name="id" value="chnaz5epeg4t">
          <input type="hidden" name="rand" value="zypw75dixyrvbihw4rqgzu46w7m7vkkhj2qskhtxci">
          <input type="hidden" name="referer" value="">
          <input type="hidden" name="method_free" value="">
          <input type="hidden" name="method_premium" value="">
        </form>
        </body></html>
    """.trimIndent()

    @Test
    fun echtesFormularWirdVollstaendigGelesen() {
        val form = hoster.downloadForm(echteSeite, "chnaz5epeg4t")
        assertEquals("download2", form["op"])
        assertEquals("chnaz5epeg4t", form["id"])
        assertEquals("zypw75dixyrvbihw4rqgzu46w7m7vkkhj2qskhtxci", form["rand"])
        assertEquals("1", form["method_premium"])
        assertEquals("", form["method_free"])
    }

    /**
     * Fuer angemeldete Nutzer liefert die Seite teilweise kein Formular.
     * Daran darf die Aufloesung nicht scheitern - genau das war der Fehler
     * "Download-Formular nicht gefunden".
     */
    @Test
    fun ohneFormularWerdenDieFelderSelbstAufgebaut() {
        val form = hoster.downloadForm("<html><body>kein Formular</body></html>", "abc123def456")
        assertEquals("download2", form["op"])
        assertEquals("abc123def456", form["id"])
        assertEquals("1", form["method_premium"])
    }

    /**
     * "ddownload.com" enthaelt die Zeichenfolge "download": das alte Muster
     * hielt daher jede Seitenadresse fuer den Direktlink.
     */
    @Test
    fun seitenlinksGeltenNichtAlsDirektlink() {
        assertNull(hoster.extractDirectLink(echteSeite))
    }

    /**
     * Die Dateiseite enthaelt im Script einen Tracker-Aufruf auf der
     * Hauptdomain (cgi-bin/tracker.cgi). Der galt frueher als Direktlink,
     * wodurch das download2-Formular nie abgeschickt wurde.
     */
    @Test
    fun trackerCgiGiltNichtAlsDirektlink() {
        assertTrue(echteSeite.contains("tracker.cgi"))
        assertNull(hoster.extractDirectLink(echteSeite))
        assertNull(
            hoster.extractDirectLink(
                """<script>x="https://ddownload.com/cgi-bin/tracker.cgi?file_code=chnaz5epeg4t"</script>"""
            )
        )
    }

    @Test
    fun echterDirektlinkWirdErkannt() {
        val html = """<a href="https://s12.ddownload.com/xyz/scn-smps8-S37.part1.rar">Download</a>"""
        val link = hoster.extractDirectLink(html)
        assertNotNull(link)
        assertEquals("https://s12.ddownload.com/xyz/scn-smps8-S37.part1.rar", link)
    }

    @Test
    fun direktlinkAuchZwischenSeitenlinks() {
        val html = echteSeite.replace(
            "</form>",
            """</form><a href="https://s45.ddownload.com/d/abc/scn-smps8-S37E02.rar">Download</a>"""
        )
        assertEquals("https://s45.ddownload.com/d/abc/scn-smps8-S37E02.rar", hoster.extractDirectLink(html))
    }

    /** Die Location der Formular-Antwort zaehlt nur, wenn sie auf einen Fileserver zeigt. */
    @Test
    fun weiterleitungszielNurVomFileserver() {
        assertTrue(hoster.isFileServerUrl("https://s12.ddownload.com/xyz/scn-smps8-S37.part1.rar"))
        assertTrue(hoster.isFileServerUrl("http://fs-1.ddownload.com/d/abc/name.mkv"))
        assertFalse(hoster.isFileServerUrl("/login.html"))
        assertFalse(hoster.isFileServerUrl("https://ddownload.com/login.html"))
        assertFalse(hoster.isFileServerUrl("https://ddownload.com/chnaz5epeg4t"))
        assertFalse(hoster.isFileServerUrl("https://www.ddownload.com/xyz/name.rar"))
        assertFalse(hoster.isFileServerUrl("https://ddownload.com/cgi-bin/tracker.cgi?file_code=chnaz5epeg4t"))
        assertFalse(hoster.isFileServerUrl("https://s12.ddownload.com/cgi-bin/tracker.cgi?file_code=chnaz5epeg4t"))
        assertFalse(hoster.isFileServerUrl("https://cdn.ddownload.com/assets/style.css"))
    }
}
