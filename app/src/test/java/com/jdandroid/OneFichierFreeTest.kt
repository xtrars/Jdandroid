package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.OneFichierBlock
import com.jdandroid.hoster.OneFichierFreePage
import com.jdandroid.hoster.OneFichierHoster
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free-Modus von 1fichier, geprüft gegen die 404-Seite (abgerufen am
 * 04.09.2026) und die Seitenausschnitte der Referenz-Clients (JDownloader,
 * pyLoad, 1fichier-dl): Dateiseite mit Formular `f1`, Countdown `var count`,
 * Antwort mit "Click here to download", Sperr- und Hinweistexte.
 */
class OneFichierFreeTest {

    private val hoster = OneFichierHoster()

    /** Echte 404-Seite (Ausschnitt). */
    private val seite404 = """
        <div class="spacer" id="hspacer" style="height:104px"></div>
        <div class="center-container2">
            <div class="notice alc">
                The requested file does not exist<br/><span style="color:red;font-weight:bold">It could be deleted by its owner</span>.
            </div>
        </div>
    """.trimIndent()

    /** Dateiseite mit Download-Formular (Aufbau nach JDownloader/pyLoad). */
    private val dateiseite = """
        <html><head><title>Download archiv.part1.rar</title>
        <script src="https://static.1fichier.com/js/jquery.min.js"></script>
        <script>var count = 30; function cnt(){ if (count > 0) { count--; setTimeout(cnt, 1000);} }</script>
        </head><body>
        <form method="post" action="https://1fichier.com/login.pl"><input type="text" name="mail"><input type="password" name="pass"></form>
        <table class="premium">
          <tr><td class="normal">Filename :</td><td class="normal">archiv.part1.rar</td></tr>
          <tr><td class="normal">Date :</td><td class="normal">04/09/2026</td></tr>
          <tr><td class="normal">Size :</td><td class="normal">1.50 GB</td></tr>
        </table>
        <table><tr><td>Without subscription, you can download only one file at a time</td></tr></table>
        <form action="https://1fichier.com/?abcde12345" method="post" id="f1">
          <input type="hidden" name="adz" value="4711abc&amp;x" />
          <input type="hidden" name="save" value="1" />
          <input type="hidden" name="did" value="0" />
          <input type="submit" name="dl" id="dlb" value="Free download" disabled="disabled" />
        </form>
        </body></html>
    """.trimIndent()

    private val antwortMitLink = """
        <div style="width:600px;height:80px;margin:auto;text-align:center;vertical-align:middle">
        <a href="https://a-3.1fichier.com/c1234567890?x=1&amp;y=2" class="ok btn-general btn-orange">Click here to download the file</a>
        </div>
    """.trimIndent()

    @Test
    fun offlineWirdAnEchter404SeiteErkannt() {
        assertTrue(OneFichierFreePage.isOffline(seite404))
        assertTrue(OneFichierFreePage.isOffline("<div>File not found</div>"))
        assertTrue(OneFichierFreePage.isOffline("The requested file has been deleted"))
        assertFalse(OneFichierFreePage.isOffline(dateiseite))
        val block = OneFichierFreePage.classify(OneFichierFreePage.visibleText(seite404))
        assertTrue(block is OneFichierBlock.Permanent)
    }

    @Test
    fun nameUndGroesseAusDerDateiseite() {
        assertEquals("archiv.part1.rar", OneFichierFreePage.fileName(dateiseite))
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), OneFichierFreePage.fileSize(dateiseite))
        // Titel als Rueckfall, franzoesische Einheiten
        assertEquals("film.mkv", OneFichierFreePage.fileName("<title>Download film.mkv</title>"))
        assertEquals(700L * 1024 * 1024, OneFichierFreePage.fileSize("<td>Taille :</td><td>700 Mo</td>"))
        assertEquals(-1, OneFichierFreePage.fileSize(seite404))
        assertEquals(1536, OneFichierFreePage.toBytes("1,5", "KB"))
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, OneFichierFreePage.toBytes("2", "TB"))
    }

    @Test
    fun countdownVarianten() {
        assertEquals(30, OneFichierFreePage.countdownSeconds(dateiseite))
        assertEquals(45, OneFichierFreePage.countdownSeconds("Free download in ⏳ 45"))
        assertEquals(60, OneFichierFreePage.countdownSeconds("var ct = 60;"))
        assertEquals(0, OneFichierFreePage.countdownSeconds(seite404))
    }

    @Test
    fun downloadFormularMitVerstecktenFeldernOhneSave() {
        val form = OneFichierFreePage.downloadForm(dateiseite, "abcde12345")
        assertNotNull(form)
        assertEquals("https://1fichier.com/?abcde12345", form!!.action)
        assertEquals("4711abc&x", form.fields["adz"])
        assertEquals("0", form.fields["did"])
        assertFalse(form.fields.containsKey("save"))
        assertFalse(form.fields.containsKey("dl"))
        // Das Login-Formular (Feld "mail") ist nicht das Download-Formular
        assertFalse(form.needsPassword)
        assertNull(OneFichierFreePage.downloadForm(seite404, "abcde12345"))
    }

    @Test
    fun passwortgeschuetzteDateiWirdErkannt() {
        val html = dateiseite.replace(
            """<input type="hidden" name="did" value="0" />""",
            """<input type="hidden" name="did" value="0" /><input type="password" name="pass" id="pass">"""
        )
        val form = OneFichierFreePage.downloadForm(html, "abcde12345")
        assertNotNull(form)
        assertTrue(form!!.needsPassword)
    }

    @Test
    fun captchaNurBeiMarkierungImFormular() {
        assertFalse(OneFichierFreePage.hasCaptcha(dateiseite))
        val turnstile = dateiseite.replace(
            """<input type="hidden" name="did" value="0" />""",
            """<div class="cf-turnstile" data-sitekey="0x4AAAAAAA"></div>"""
        )
        assertTrue(OneFichierFreePage.hasCaptcha(turnstile))
        assertTrue(OneFichierFreePage.hasCaptcha("""<form method="post"><div class="g-recaptcha" data-sitekey="6L"></div></form>"""))
        assertTrue(OneFichierFreePage.hasCaptcha("""<form method="post"><div class="h-captcha"></div></form>"""))
        assertTrue(OneFichierFreePage.hasCaptcha("""<form id="challenge-form" method="post"></form>"""))
    }

    @Test
    fun direktlinkAusDerAntwort() {
        assertEquals("https://a-3.1fichier.com/c1234567890?x=1&y=2", OneFichierFreePage.directLink(antwortMitLink))
        assertEquals(
            "https://a-12.1fichier.com/abc123",
            OneFichierFreePage.directLink("""<a href="https://a-12.1fichier.com/abc123">Start your download</a>""")
        )
        assertEquals(
            "https://a-5.desfichiers.com/p9x8y7",
            OneFichierFreePage.directLink("""<script>window.location = 'https://a-5.desfichiers.com/p9x8y7';</script>""")
        )
        assertEquals(
            "https://a-1.1fichier.com/c99999",
            OneFichierFreePage.directLink("""<a class="ok btn-general btn-orange" href="https://a-1.1fichier.com/c99999">Download</a>""")
        )
        assertNull(OneFichierFreePage.directLink(dateiseite))
        assertNull(OneFichierFreePage.directLink(seite404))
    }

    @Test
    fun wartezeitenMitZeitangabe() {
        fun wait(text: String): Int = (OneFichierFreePage.classify(text) as OneFichierBlock.Wait).seconds
        assertEquals(5 * 60 + 1, wait("> You must wait 5 minutes"))
        assertEquals(10 * 60 + 1, wait("Warning! you must wait at least 10 minutes between each downloads."))
        assertEquals(3 * 60 + 1, wait("You must wait up to 3 minutes"))
        assertEquals(7 * 60 + 1, wait("""<div id="dlw"> Vous devez attendre encore 7 minutes</div>"""))
        assertEquals(3600 + 1, wait("> IP Locked"))
        assertEquals(3600 + 1, wait("> Will be unlocked within 1h."))
        val text = (OneFichierFreePage.classify("You must wait 5 minutes") as OneFichierBlock.Wait).text
        assertEquals(Texts.t("hoster_onefichier_next_free_in", Texts.t("hoster_duration_minutes", 5)), text)
    }

    @Test
    fun nurEinDownloadJeAdresseIstFuenfMinuten() {
        val texte = listOf(
            "> You already downloading a file",
            "> You already downloading some file",
            "> You can download only one file at a time",
            "> You must wait for another download",
            "> Please wait a few seconds before downloading new ones",
            "Without subscription, you must wait between downloads",
            "> Téléchargements en cours",
            "> Votre adresse IP ouvre trop de connexions vers le serveur"
        )
        for (t in texte) {
            val block = OneFichierFreePage.classify(t)
            assertTrue(t, block is OneFichierBlock.Wait)
            assertEquals(t, 5 * 60 + 1, (block as OneFichierBlock.Wait).seconds)
        }
    }

    @Test
    fun weitereVoruebergehendeZustaende() {
        fun wait(text: String): Int = (OneFichierFreePage.classify(text) as OneFichierBlock.Wait).seconds
        assertEquals(61, wait("> Free download is temporarily limited due to high demand"))
        assertEquals(31, wait("> Your requests are too fast"))
        assertEquals(5 * 60 + 1, wait("> Software error: <"))
        assertEquals(5 * 60 + 1, wait("Can't connect DB"))
        assertEquals(20 * 60 + 1, wait("Our services are in maintenance"))
        assertEquals(
            3600 + 1,
            wait("The free offer is intended to occasional use. You already downloaded for free more than 5 files today.")
        )
        val vpn = OneFichierFreePage.classify(
            "Accès restreint – professional infrastructure detected. This IP address has been identified as belonging to a server, proxy, VPN"
        )
        assertTrue(vpn is OneFichierBlock.Transient)
        assertTrue((vpn as OneFichierBlock.Transient).text.contains("VPN"))
    }

    @Test
    fun endgueltigeGruendeMitMeldung() {
        fun perm(text: String): String = (OneFichierFreePage.classify(text) as OneFichierBlock.Permanent).text
        assertEquals(Texts.t("hoster_onefichier_account_required"), perm("The download is not possible to free unregistered users"))
        assertEquals(Texts.t("hoster_onefichier_account_required"), perm("This file need a subscription"))
        assertEquals(Texts.t("hoster_onefichier_access_restricted"), perm("> Access to this file is protected"))
        assertEquals(Texts.t("hoster_onefichier_access_restricted"), perm("> This file is protected"))
        assertEquals(Texts.t("hoster_onefichier_bad_password"), perm("Bad password"))
        assertEquals(Texts.t("hoster_file_offline"), perm("> File not found"))
    }

    @Test
    fun normaleDateiseiteIstKeineSperre() {
        // "you can download only one file at a time" steht als Hinweis auf jeder
        // Free-Dateiseite: mit Download-Formular ist das keine Sperre - sonst
        // wartete der Eintrag endlos in 5-Minuten-Schritten, ohne je zu laden
        val text = OneFichierFreePage.visibleText(dateiseite)
        assertNotNull(OneFichierFreePage.downloadForm(dateiseite, "abcde12345"))
        assertNull(OneFichierFreePage.classify(text, downloadOffered = true))
        // Ohne Formular (Antwort auf das Formular) gilt derselbe Satz als Sperre
        assertTrue(OneFichierFreePage.classify(text) is OneFichierBlock.Wait)
        // Sperren mit Zeitangabe und endgueltige Gruende gelten auch mit Formular
        assertTrue(OneFichierFreePage.classify("$text You must wait 5 minutes", downloadOffered = true) is OneFichierBlock.Wait)
        assertTrue(OneFichierFreePage.classify("$text This file need a subscription", downloadOffered = true) is OneFichierBlock.Permanent)
        // Muster nur im sichtbaren Text, nicht in Skripten
        assertNull(OneFichierFreePage.classify(OneFichierFreePage.visibleText("<script>var m = 'You must wait 5 minutes';</script>")))
    }

    @Test
    fun hinweisseiteOhneDownloadLinkLiefertKeinenDirektlink() {
        // Sperr-/Hinweisseite mit Links auf Subdomains: keiner davon ist die Datei
        val hinweis = """
            <html><head><link rel="stylesheet" href="https://static.1fichier.com/css/main.css">
            <script src="https://static.1fichier.com/js/jquery.min.js"></script></head>
            <body><a href="https://www.1fichier.com/register.pl">Register</a>
            <a href="https://1fichier.com/?abcde12345">Back</a>
            <img src="https://img.1fichier.com/logo1">
            <div>You must wait 5 minutes</div></body></html>
        """.trimIndent()
        assertNull(OneFichierFreePage.directLink(hinweis))
        assertNull(OneFichierFreePage.directLink("""<script>window.location = 'https://www.1fichier.com/login.pl';</script>"""))
        // Der echte Link steht auch zwischen solchen Links
        assertEquals(
            "https://a-3.1fichier.com/c1234567890",
            OneFichierFreePage.directLink(hinweis.replace("<div>", """<a href="https://a-3.1fichier.com/c1234567890">Click here to download</a><div>"""))
        )
    }

    @Test
    fun fileserverRegelKenntNurEinPfadsegmentOhneEndung() {
        assertTrue(OneFichierFreePage.isFileServerUrl("https://a-3.1fichier.com/c1234567890"))
        assertTrue(OneFichierFreePage.isFileServerUrl("http://a-12.desfichiers.com/p9x8y7?x=1&y=2"))
        assertFalse(OneFichierFreePage.isFileServerUrl("https://www.1fichier.com/register.pl"))
        assertFalse(OneFichierFreePage.isFileServerUrl("https://www.1fichier.com/abcdef"))
        assertFalse(OneFichierFreePage.isFileServerUrl("https://static.1fichier.com/css/main.css"))
        assertFalse(OneFichierFreePage.isFileServerUrl("https://img.1fichier.com/logo1"))
        assertFalse(OneFichierFreePage.isFileServerUrl("https://a-3.1fichier.com/c123/extra"))
        assertFalse(OneFichierFreePage.isFileServerUrl("https://a-3.1fichier.com/main.css"))
        assertFalse(OneFichierFreePage.isFileServerUrl("https://1fichier.com/?abcde12345"))
    }

    @Test
    fun fileserverAdressenErkannt() {
        assertTrue(hoster.isDirectDownloadUrl("https://a-3.1fichier.com/c1234567890"))
        assertTrue(hoster.isDirectDownloadUrl("http://a-12.desfichiers.com/p9x8y7?x=1"))
        assertTrue(hoster.isDirectDownloadUrl("https://a-3.1fichier.com/archiv.part1.rar"))
        assertFalse(hoster.isDirectDownloadUrl("https://1fichier.com/?abcde12345"))
        assertFalse(hoster.isDirectDownloadUrl("https://www.1fichier.com/?abcde12345&lg=en"))
        assertFalse(hoster.isDirectDownloadUrl("https://static.1fichier.com/js/jquery.min.js"))
        assertFalse(hoster.isDirectDownloadUrl("https://1fichier.com/register.pl"))
        assertFalse(hoster.isDirectDownloadUrl("https://challenges.cloudflare.com/turnstile/v0/api.js"))
    }

    @Test
    fun resolveFreeUebernimmtBrowserDirektlink() = runBlocking {
        Http.browserUserAgent = "Mozilla/5.0 (Test) WebView"
        val link = hoster.resolveFree(
            "https://1fichier.com/?abcde12345",
            FreeHints(direktUrlAusBrowser = "http://a-3.1fichier.com/c1234567890", cookies = "LG=en; SID=abc")
        )
        // Cleartext ist gesperrt: Fileserver-Link ueber HTTPS
        assertEquals("https://a-3.1fichier.com/c1234567890", link.directUrl)
        assertEquals("LG=en; SID=abc", link.headers["Cookie"])
        assertEquals("Mozilla/5.0 (Test) WebView", link.headers["User-Agent"])
        assertEquals("https://1fichier.com/?abcde12345", link.headers["Referer"])
        val ohneCookies = hoster.resolveFree("https://1fichier.com/?abcde12345", FreeHints(direktUrlAusBrowser = "https://a-3.1fichier.com/c1"))
        assertFalse(ohneCookies.headers.containsKey("Cookie"))
        // Fremder Host: Link zaehlt, die Session-Cookies gehen nicht mit
        val fremd = hoster.resolveFree(
            "https://1fichier.com/?abcde12345",
            FreeHints(direktUrlAusBrowser = "https://cdn.example.net/abc/name.rar", cookies = "SID=abc")
        )
        assertEquals("https://cdn.example.net/abc/name.rar", fremd.directUrl)
        assertFalse(fremd.headers.containsKey("Cookie"))
    }

    @Test
    fun ungueltigerLinkIstPermanent() = runBlocking {
        try {
            hoster.resolveFree("https://1fichier.com/register.pl", FreeHints())
            assertTrue("Ausnahme erwartet", false)
        } catch (e: HosterException) {
            assertTrue(e.permanent)
        }
    }
}
