package com.jdandroid

import com.jdandroid.hoster.DdownloadHoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Based on the actual ddownload.com page layout. */
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

    /** For logged-in users the page sometimes has no form; resolving must not fail on that. */
    @Test
    fun ohneFormularWerdenDieFelderSelbstAufgebaut() {
        val form = hoster.downloadForm("<html><body>kein Formular</body></html>", "abc123def456")
        assertEquals("download2", form["op"])
        assertEquals("abc123def456", form["id"])
        assertEquals("1", form["method_premium"])
    }

    /** "ddownload.com" contains "download": a naive pattern takes every page URL for a direct link. */
    @Test
    fun seitenlinksGeltenNichtAlsDirektlink() {
        assertNull(hoster.extractDirectLink(echteSeite))
    }

    /** The page script calls a tracker on the main domain (cgi-bin/tracker.cgi); it is not a direct link. */
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

    /** The Location of the form response only counts when it points to a file server. */
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
        // File server with port, dl.cgi path and foreign CDN host are direct links
        assertTrue(hoster.isFileServerUrl("https://fs07.ddownload.com:183/d/abc123/name.part1.rar"))
        assertTrue(hoster.isFileServerUrl("https://s12.ddownload.com/cgi-bin/dl.cgi/abc/name.rar"))
        assertTrue(hoster.isFileServerUrl("https://cdn7.ddl-cdn.net/d/abc/name.mkv?token=1"))
        assertFalse(hoster.isFileServerUrl("https://ddownload.com/premium.html"))
    }
}
