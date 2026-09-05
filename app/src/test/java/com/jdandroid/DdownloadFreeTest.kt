package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.DdownloadFreePage
import com.jdandroid.hoster.DdownloadHoster
import com.jdandroid.hoster.FreeCaptcha
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.WaitException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free-Modus von ddownload, geprüft gegen die tatsächliche Dateiseite
 * (abgerufen am 04.09.2026): Turnstile im Download-Formular, Countdown 60 s,
 * Fehlermeldungen in dk-dl-alert und als Sprechblase am gesperrten Knopf.
 * Dazu die klassischen XFileSharing-Muster (Span-Captcha, "You have to wait").
 */
class DdownloadFreeTest {

    private val hoster = DdownloadHoster()

    /** Ausschnitt der echten Dateiseite (GET, ohne Fehler). */
    private val seite = """
        <html><head><title>Download scn smps8 S37E02 rar</title></head><body>
        <form name="F1" method="POST" action="" style="display:contents;">
            <input type="hidden" name="op" value="download2">
            <input type="hidden" name="id" value="chnaz5epeg4t">
            <input type="hidden" name="rand" value="4n4yng6b5hgxdjgygbu7oom6r5apolomedgsk57f2e">
            <input type="hidden" name="referer" value="">
            <input type="hidden" name="method_free" value="">
            <input type="hidden" name="method_premium" value="">
            <div class="dk-dl-icon" data-fn="scn-smps8-S37E02.rar">
            <h2 class="dk-dl-name">scn-smps8-S37E02.rar</h2>
            <p class="dk-dl-size">663.63 MB</p>
            <div class="dk-dl-captcha">
                <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" defer></script>
                <div class="cf-turnstile" data-sitekey="0x4AAAAAABm53D0OJNkESa1O" id="cf-turnstile-widget"></div>
            </div>
            <div class="dk-countdown" id="dk2Countdown">
                <span class="dk-countdown-num" id="dk2CountdownNum">60</span>
                <span class="dk-countdown-label">Bitte warte vor dem Download</span>
            </div>
            <button type="button" id="downloadbtn" class="dk-dl-btn dk-btn-disabled" disabled>Normaler Download</button>
        </form>
        <script>
            var dkTpl = 'Du musst noch {t} warten bis zum nächsten Download';
            var body = 'op=guest_checkout&create_account=1&cf-'+'turnstile-response=' + encodeURIComponent(cfToken);
        </script>
        </body></html>
    """.trimIndent()

    /** Antwort auf op=download2 ohne gültiges Turnstile-Token. */
    private val wrongCaptcha = seite.replace(
        """<button type="button" id="downloadbtn" class="dk-dl-btn dk-btn-disabled" disabled>Normaler Download</button>""",
        """<div class="dk-dl-alert">Wrong captcha</div>
           <div class="dk-toast-wrap"><div id="dk2Toast" class="dk-toast"><div class="dk-toast-msg" id="dk2ToastMsg"></div></div>
           <button type="button" id="downloadbtn" class="dk-dl-btn dk-btn-disabled dk-btn-blocked" data-toast-msg="Wrong captcha" data-wait-seconds="0">Normaler Download</button></div>"""
    )

    private val expiredSession = wrongCaptcha.replace("Wrong captcha", "Expired download session")

    /** Sperre: der Knopf trägt die Rohsekunden. */
    private val gesperrt = wrongCaptcha
        .replace("""<div class="dk-dl-alert">Wrong captcha</div>""", "")
        .replace("""data-toast-msg="Wrong captcha" data-wait-seconds="0"""",
            """data-toast-msg="You have to wait 1 hour, 2 minutes, 3 seconds till next download" data-wait-seconds="3723"""")

    @Test
    fun echteSeiteHatTurnstileUndCountdownOhneSperre() {
        assertEquals(FreeCaptcha.Browser("Turnstile"), DdownloadFreePage.captcha(seite))
        assertEquals(60, DdownloadFreePage.countdownSeconds(seite))
        assertEquals(0, DdownloadFreePage.waitSeconds(seite))
        assertNull(DdownloadFreePage.alert(seite))
        assertNull(DdownloadFreePage.toast(seite))
        assertNull(DdownloadFreePage.premiumOnlyReason(seite))
        assertFalse(DdownloadFreePage.isOffline(seite))
        assertFalse(DdownloadFreePage.isLimitWithoutTime(seite))
        assertFalse(DdownloadFreePage.isWrongCaptcha(seite))
    }

    @Test
    fun formularfehlerWerdenAusAlertUndSprechblaseGelesen() {
        assertEquals("Wrong captcha", DdownloadFreePage.alert(wrongCaptcha))
        assertEquals("Wrong captcha", DdownloadFreePage.toast(wrongCaptcha))
        assertTrue(DdownloadFreePage.isWrongCaptcha(wrongCaptcha))
        assertFalse(DdownloadFreePage.isExpiredSession(wrongCaptcha))
        assertEquals(0, DdownloadFreePage.waitSeconds(wrongCaptcha))

        assertEquals("Expired download session", DdownloadFreePage.alert(expiredSession))
        assertTrue(DdownloadFreePage.isExpiredSession(expiredSession))
        assertFalse(DdownloadFreePage.isWrongCaptcha(expiredSession))
    }

    @Test
    fun sperreLiefertRohsekundenVomKnopf() {
        assertEquals(3723, DdownloadFreePage.waitSeconds(gesperrt))
        assertFalse(DdownloadFreePage.isWrongCaptcha(gesperrt))
    }

    @Test
    fun klassischeWartezeitTexteWerdenGerechnet() {
        assertEquals(3723, DdownloadFreePage.parseWaitText(" 1 hour, 2 minutes, 3 seconds till next download"))
        assertEquals(2 * 86_400, DdownloadFreePage.parseWaitText(" 2 days"))
        assertEquals(3723, DdownloadFreePage.parseWaitText(" 1:02:03 warten"))
        assertEquals(125, DdownloadFreePage.parseWaitText(" 2:05 warten"))
        assertEquals(0, DdownloadFreePage.parseWaitText(" {t} warten bis zum nächsten Download"))
        assertEquals(
            8 * 60,
            DdownloadFreePage.waitSeconds("""<p class="err">You have reached the download-limit: 8 minutes</p>""")
        )
        assertEquals(
            45,
            DdownloadFreePage.waitSeconds("""<b>You have to wait 45 seconds</b>""")
        )
    }

    @Test
    fun klassischerCountdownWirdErkannt() {
        assertEquals(
            30,
            DdownloadFreePage.countdownSeconds("""<span id="countdown_str">Wait <span id="secs">30</span> seconds</span>""")
        )
        assertEquals(0, DdownloadFreePage.countdownSeconds("<html></html>"))
    }

    @Test
    fun spanCaptchaWirdNachPaddingSortiert() {
        val html = """
            <td>Enter code below:</td>
            <div style="position:relative;width:80px;">
              <span style='position:absolute;padding-left:37px;padding-top:6px;font-size:22px;'>&#53;</span>
              <span style='position:absolute;padding-left:8px;padding-top:2px;font-size:22px;'>&#49;</span>
              <span style='position:absolute;padding-left:52px;padding-top:9px;font-size:22px;'>9</span>
              <span style='position:absolute;padding-left:21px;padding-top:4px;font-size:22px;'>&#55;</span>
            </div>
            <input type="text" name="code" class="captcha_code">
        """.trimIndent()
        assertEquals("1759", DdownloadFreePage.solveSpanCaptcha(html))
        assertEquals(FreeCaptcha.Span("1759"), DdownloadFreePage.captcha(html))
        val form = hoster.freeDownloadForm(html, "abc123def456", "https://ddownload.com/abc123def456", mapOf("code" to "1759"))
        assertEquals("1759", form["code"])
        assertEquals("Free Download", form["method_free"])
        assertEquals("", form["method_premium"])
    }

    @Test
    fun unvollstaendigesSpanCaptchaZaehltNicht() {
        val html = """<td>Enter code</td><div><span style="padding-left:1px">1</span><span style="padding-left:9px">2</span></div>"""
        assertNull(DdownloadFreePage.solveSpanCaptcha(html))
        assertEquals(FreeCaptcha.None, DdownloadFreePage.captcha(html))
    }

    @Test
    fun andereCaptchaArtenBrauchenDenBrowser() {
        assertEquals(
            FreeCaptcha.Image("https://ddownload.com/captchas/1a2b3c.jpg"),
            DdownloadFreePage.captcha("""<img src="https://ddownload.com/captchas/1a2b3c.jpg"><input name="code">""")
        )
        assertEquals(
            FreeCaptcha.Browser("reCAPTCHA"),
            DdownloadFreePage.captcha("""<div class="g-recaptcha" data-sitekey="6Lc..."></div>""")
        )
        assertEquals(
            FreeCaptcha.Browser("hCaptcha"),
            DdownloadFreePage.captcha("""<div class="h-captcha" data-sitekey="abc"></div>""")
        )
    }

    @Test
    fun premiumGrenzenSindDauerhaftMitMeldung() {
        val zuGross = wrongCaptcha.replace("Wrong captcha", "You can download files up to 500 MB only")
        assertEquals(
            Texts.t("hoster_ddownload_free_size_limit", "500 MB"),
            DdownloadFreePage.premiumOnlyReason(zuGross)
        )
        assertEquals(
            Texts.t("hoster_ddownload_premium_only"),
            DdownloadFreePage.premiumOnlyReason(""">This file is available for Premium Users only<""")
        )
        assertEquals(
            Texts.t("hoster_ddownload_premium_only"),
            DdownloadFreePage.premiumOnlyReason(wrongCaptcha.replace("Wrong captcha", "The file you requested reached max downloads limit for Free Users"))
        )
    }

    @Test
    fun limitOhneZeitangabeWirdErkannt() {
        assertTrue(DdownloadFreePage.isLimitWithoutTime(wrongCaptcha.replace("Wrong captcha", "Daily download limit reached")))
        assertTrue(DdownloadFreePage.isLimitWithoutTime(""">You have reached the maximum limit 150 files in 24 hours<"""))
        assertFalse(DdownloadFreePage.isLimitWithoutTime(wrongCaptcha))
    }

    @Test
    fun offlineUndWartungWerdenErkannt() {
        assertTrue(DdownloadFreePage.isOffline("<html><head><title>Download </title></head><body></body></html>"))
        assertTrue(DdownloadFreePage.isOffline("<b>File Not Found</b>"))
        assertTrue(DdownloadFreePage.isOffline("<p>This file was banned by copyright owner</p>"))
        assertFalse(DdownloadFreePage.isOffline(seite))
        assertTrue(DdownloadFreePage.isMaintenance(">This server is in maintenance mode<"))
        assertFalse(DdownloadFreePage.isMaintenance(seite))
        assertTrue(DdownloadFreePage.isSkippedCountdown("""<b class="err">Skipped countdown</b>"""))
    }

    @Test
    fun freeFormularUebernimmtRandUndSetztReferer() {
        val form = hoster.freeDownloadForm(seite, "chnaz5epeg4t", "https://ddownload.com/chnaz5epeg4t")
        assertEquals("download2", form["op"])
        assertEquals("chnaz5epeg4t", form["id"])
        assertEquals("4n4yng6b5hgxdjgygbu7oom6r5apolomedgsk57f2e", form["rand"])
        assertEquals("https://ddownload.com/chnaz5epeg4t", form["referer"])
        assertEquals("Free Download", form["method_free"])
        assertEquals("", form["method_premium"])
        assertFalse(form.containsKey("code"))
    }

    /** Der im Browser abgefangene Direktlink wird ohne Netz samt Cookies und Browser-Kennung übernommen. */
    @Test
    fun direktlinkAusDemBrowserWirdMitCookiesUebernommen() = runBlocking {
        val alt = Http.browserUserAgent
        try {
            Http.browserUserAgent = "Mozilla/5.0 (Linux; Android 14; wv) Test"
            val link = hoster.resolveFree(
                "https://ddownload.com/chnaz5epeg4t",
                FreeHints(
                    direktUrlAusBrowser = "https://s12.ddownload.com/d/abc/scn-smps8-S37E02.rar",
                    cookies = "cf_clearance=xyz; lang=german"
                )
            )
            assertEquals("https://s12.ddownload.com/d/abc/scn-smps8-S37E02.rar", link.directUrl)
            assertEquals("scn-smps8-S37E02.rar", link.fileName)
            assertEquals("cf_clearance=xyz; lang=german", link.headers["Cookie"])
            assertEquals("Mozilla/5.0 (Linux; Android 14; wv) Test", link.headers["User-Agent"])
            assertEquals("https://ddownload.com/chnaz5epeg4t", link.headers["Referer"])
        } finally {
            Http.browserUserAgent = alt
        }
    }

    @Test
    fun ohneCookiesKeinCookieHeader() = runBlocking {
        val link = hoster.resolveFree(
            "https://ddownload.com/chnaz5epeg4t",
            FreeHints(direktUrlAusBrowser = "https://fs07.ddownload.com:183/cgi-bin/dl.cgi/token123", cookies = null)
        )
        assertFalse(link.headers.containsKey("Cookie"))
        assertNull(link.fileName)
    }

    /**
     * Countdown ueber der Inline-Grenze: das Formular wird gemerkt und der
     * Folgeversuch laedt die Seite nicht erneut - sonst stuende der Countdown
     * wieder auf demselben Wert und der Eintrag kreiste ohne Fortschritt.
     */
    @Test
    fun langerCountdownMerktFormularStattSeiteNeuZuLaden() = runBlocking {
        val ohneCaptcha = seite
            .replace(Regex("""<div class="dk-dl-captcha">[\s\S]*?</div>\s*</div>"""), "")
            .replace(""">60<""", ">600<")
        assertEquals(FreeCaptcha.None, DdownloadFreePage.captcha(ohneCaptcha))
        assertEquals(600, DdownloadFreePage.countdownSeconds(ohneCaptcha))
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(ohneCaptcha).addHeader("Content-Type", "text/html"))
            server.enqueue(MockResponse().setBody(ohneCaptcha).addHeader("Content-Type", "text/html"))
            val lokal = DdownloadHoster(server.url("/").toString().trimEnd('/'))
            val url = "https://ddownload.com/chnaz5epeg4t"
            val erste = runCatching { lokal.resolveFree(url, FreeHints()) }.exceptionOrNull()
            assertTrue("$erste", erste is WaitException)
            assertEquals(601, (erste as WaitException).seconds)
            assertEquals(1, server.requestCount)
            assertEquals("/chnaz5epeg4t", server.takeRequest().path)
            // Zweiter Versuch (Engine nach Ablauf oder frueher): keine neue Seite, Restzeit
            val zweite = runCatching { lokal.resolveFree(url, FreeHints()) }.exceptionOrNull()
            assertTrue("$zweite", zweite is WaitException)
            assertTrue((zweite as WaitException).seconds in 590..601)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ungueltigerLinkScheitertDauerhaftAuchImFreeModus() = runBlocking {
        val fehler = runCatching {
            hoster.resolveFree("https://ddownload.com/register.html", FreeHints())
        }.exceptionOrNull()
        assertTrue(fehler is com.jdandroid.hoster.HosterException && fehler.permanent)
        assertFalse(fehler is CaptchaRequiredException)
    }

    /** Free-Antworten leiten auch auf Fileserver-Pfade ohne Dateiendung. */
    @Test
    fun fileserverPfadeOhneEndungGeltenAlsDirektlink() {
        assertTrue(hoster.isDirectDownloadUrl("https://fs07.ddownload.com:183/cgi-bin/dl.cgi/token123"))
        assertTrue(hoster.isDirectDownloadUrl("https://s12.ddownload.com/d/abc123"))
        assertTrue(hoster.isDirectDownloadUrl("https://s12.ddownload.com/files/abc123"))
        assertFalse(hoster.isDirectDownloadUrl("https://ddownload.com/d/chnaz5epeg4t"))
        assertFalse(hoster.isDirectDownloadUrl("https://my.ddownload.com/d/abc"))
        assertFalse(hoster.isDirectDownloadUrl("https://s12.ddownload.com/cgi-bin/tracker.cgi?file_code=x"))
        assertFalse(hoster.isDirectDownloadUrl("https://s12.ddownload.com/"))
        assertFalse(hoster.isDirectDownloadUrl("https://challenges.cloudflare.com/turnstile/v0/api.js"))
    }
}
