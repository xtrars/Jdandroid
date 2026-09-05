package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.RapidgatorBlock
import com.jdandroid.hoster.RapidgatorFreePage
import com.jdandroid.hoster.RapidgatorHoster
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rapidgator free mode against the actual pages: file page with timer
 * variables, AjaxStartTimer/AjaxGetDownloadLink answers, Turnstile captcha
 * page. Limit texts come from the reference plugins (JDownloader, pyLoad).
 */
class RapidgatorFreeTest {

    private val hoster = RapidgatorHoster()

    /** Excerpt of the real file page. */
    private val seite = """
        <html><head><title>Download file c20-fundamentals.part02.rar</title></head><body>
        <div class="btm" style="height: 95px;">
            <p style="word-wrap: break-word;width: 490px;line-height: 19px;">
                <strong>
                    Downloading:
                </strong>
                <a href="">
                c20-fundamentals.part02.rar                    </a>
                <br/>
            </p>
            <div>
                File size:
                <strong>1 GB</strong>
<!--                    -->                </div>
        </div>
        <a href="#" class="link act-link btn-free">
            SLOW SPEED DOWNLOAD                                </a>
        <td class="descr">Download restriction:</td>
        <td>
            1 file per                                                                120 minutes                            </td>
        <script type="text/javascript">
            var startTimerUrl = '/download/AjaxStartTimer';
            var getDownloadUrl = '/download/AjaxGetDownloadLink';
            var captchaUrl = '/download/captcha';
            var copyUrl = '/download/AjaxCopyFile';
            var secs = 180;
            var mil_secs = 0;
            var download_link = '';
            var sid = '';
            var fid = 638602268;
            var premium_download_link = '';
            var is_premium = 0;
            var infobar_waitmsg = 'Please hold, download will start in <span class=\"seconds\"></span> seconds.';
            $("div").on('click', '.btn-download', function() {
                location.href = download_link;
                return false;
            });
        </script>
        </body></html>
    """.trimIndent()

    /** Excerpt of the real captcha page (GET /download/captcha). */
    private val captchaSeite = """
        <html><head><title>Rapidgator: Fast, safe and secure file hosting</title>
        <script>var captchaCallback = function(response){jQuery('#DownloadCaptchaForm_verifyCode').val(response);};</script>
        </head><body>
        <div class="btm3">
            <form id="captchaform" action="/download/captcha" method="post">                <strong style="color:red">
                </strong>
            <label for="DownloadCaptchaForm_captcha"></label>                <input id="DownloadCaptchaForm_verifyCode" type="hidden" name="DownloadCaptchaForm[verifyCode]" /><div class="cf-turnstile" data-sitekey="0x4AAAAAACGwIXkmZ2lsGdCV" data-callback="captchaCallback" data-theme="light" data-language="en"></div>
            <a href="#" id="submit-button" class="btn" onclick="document.forms.captchaform.submit();">
            </form>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun dateiKennungUndNameAusDemLink() {
        assertEquals(
            "0d348b3c239fe48ea3fed28b8810190d" to "c20-fundamentals.part02.rar.html",
            RapidgatorFreePage.fileIdAndName(
                "https://rapidgator.net/file/0d348b3c239fe48ea3fed28b8810190d/c20-fundamentals.part02.rar.html"
            )
        )
        assertEquals(
            "0d348b3c239fe48ea3fed28b8810190d" to null,
            RapidgatorFreePage.fileIdAndName("https://rg.to/file/0d348b3c239fe48ea3fed28b8810190d")
        )
        assertEquals("638602268" to null, RapidgatorFreePage.fileIdAndName("https://rapidgator.net/file/638602268"))
        assertNull(RapidgatorFreePage.fileIdAndName("https://rapidgator.net/article/premium"))
    }

    @Test
    fun nameGroesseUndTimerVariablenDerDateiseite() {
        assertEquals("c20-fundamentals.part02.rar", RapidgatorFreePage.fileName(seite))
        assertEquals(1024L * 1024 * 1024, RapidgatorFreePage.fileSize(seite))
        val vars = RapidgatorFreePage.freeVars(seite)!!
        assertEquals("638602268", vars.fid)
        assertEquals(180, vars.secs)
        assertEquals("/download/AjaxStartTimer", vars.startTimerUrl)
        assertEquals("/download/AjaxGetDownloadLink", vars.getDownloadUrl)
        assertEquals("/download/captcha", vars.captchaUrl)
        assertNull(RapidgatorFreePage.md5(seite))
        // The static table row "1 file per 120 minutes" is not a block
        assertNull(RapidgatorFreePage.pageBlock(seite))
        assertFalse(RapidgatorFreePage.isOffline(seite))
    }

    @Test
    fun nameAusDemTitelWennDerBlockFehlt() {
        val html = "<html><head><title>Download file  video.mkv </title></head><body></body></html>"
        assertEquals("video.mkv", RapidgatorFreePage.fileName(html))
        assertEquals(-1L, RapidgatorFreePage.fileSize(html))
        assertNull(RapidgatorFreePage.freeVars(html))
    }

    @Test
    fun groessenSind1024Basiert() {
        assertEquals(663L * 1024 * 1024 + (0.63 * 1024 * 1024).toLong(), RapidgatorFreePage.toBytes("663.63", "MB"))
        assertEquals(512L * 1024, RapidgatorFreePage.toBytes("512", "KB"))
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, RapidgatorFreePage.toBytes("2", "TB"))
        assertEquals(1536L * 1024 * 1024, RapidgatorFreePage.fileSize("File size: <strong>1,5 GB</strong>"))
    }

    @Test
    fun ajaxAntwortenDesTimers() {
        val started = RapidgatorFreePage.ajaxReply("""{"state":"started","sid":"rlSR5cec4Frbfim01gutninkwce84cc0"}""")!!
        assertEquals("started", started.state)
        assertEquals("rlSR5cec4Frbfim01gutninkwce84cc0", started.sid)
        assertNull(started.code)

        val early = RapidgatorFreePage.ajaxReply(
            """{"state":"error","code":"You didn`t wait specified time. Try again or contact to administrator","0":"step3"}"""
        )!!
        assertEquals("error", early.state)
        assertTrue(early.code!!.startsWith("You didn`t wait"))
        assertEquals(RapidgatorBlock.Restart, RapidgatorFreePage.classify(early.code!!))

        val done = RapidgatorFreePage.ajaxReply("""{"state":"done"}""")!!
        assertEquals("done", done.state)
        assertNull(done.sid)
        assertNull(RapidgatorFreePage.ajaxReply("<html>Just a moment</html>"))
        assertEquals("a\"b/c", RapidgatorFreePage.ajaxReply("""{"state":"error","code":"a\"b\/c"}""")!!.code)
    }

    @Test
    fun captchaSeiteErkanntUndOhneDirektlink() {
        assertTrue(RapidgatorFreePage.hasCaptchaForm(captchaSeite))
        assertNull(RapidgatorFreePage.directLink(captchaSeite))
        assertFalse(RapidgatorFreePage.hasCaptchaForm(seite))
    }

    @Test
    fun direktlinkAusDerSeiteNachDemCaptcha() {
        val html = """<script>$("div").on('click', '.btn-download', function() {
            location.href = 'https://pr5.rapidgator.net//?r=download/index&session_id=Ab12Cd34';</script>"""
        assertEquals(
            "https://pr5.rapidgator.net//?r=download/index&session_id=Ab12Cd34",
            RapidgatorFreePage.directLink(html)
        )
        assertEquals(
            "https://pr_srv.rapidgator.net//?r=download/index&session_id=xyz",
            RapidgatorFreePage.directLink("""showReadyPage("https://pr_srv.rapidgator.net//?r=download/index&amp;session_id=xyz")""")
        )
        assertEquals(
            "https://pr7.rapidgator.net/download/abc",
            RapidgatorFreePage.directLink("function f(){ return 'https://pr7.rapidgator.net/download/abc'; }")
        )
        assertNull(RapidgatorFreePage.directLink("<a href='https://rapidgator.net/article/premium'>"))
    }

    @Test
    fun premiumGrenzenSindEndgueltigMitMeldung() {
        val zuGross = RapidgatorFreePage.classify(
            "You can download files up to 500 MB in free mode. Upgrade to premium"
        ) as RapidgatorBlock.Permanent
        assertEquals(Texts.t("hoster_rapidgator_free_size_limit", "500 MB"), zuGross.text)
        assertTrue(RapidgatorFreePage.classify("This file can be downloaded by premium only<") is RapidgatorBlock.Permanent)
        assertTrue(
            RapidgatorFreePage.classify(
                "The files of this publisher \"someone\" can be downloaded only by subscribers."
            ) is RapidgatorBlock.Permanent
        )
    }

    @Test
    fun sperrenMitZeitangabeWerdenZurWartezeit() {
        val delay = RapidgatorFreePage.classify("Delay between downloads must be not less than 120 min") as RapidgatorBlock.Wait
        assertEquals(120 * 60 + 1, delay.seconds)
        assertEquals(Texts.t("hoster_rapidgator_next_free_in", Texts.t("hoster_duration_hours", 2)), delay.text)
        assertEquals(10 * 60 + 1, (RapidgatorFreePage.classify("Try again in 10 minutes") as RapidgatorBlock.Wait).seconds)
        assertEquals(31, (RapidgatorFreePage.classify("Try again in 30 seconds") as RapidgatorBlock.Wait).seconds)
        assertEquals(3601, (RapidgatorFreePage.classify("Try again in 1 hour") as RapidgatorBlock.Wait).seconds)
    }

    @Test
    fun limitsOhneZeitangabeBekommenFesteWartezeit() {
        assertEquals(3 * 3600, (RapidgatorFreePage.classify("You have reached your daily downloads limit") as RapidgatorBlock.Wait).seconds)
        assertEquals(
            3 * 3600,
            (RapidgatorFreePage.classify("Error. Link expired. You have reached your daily limit of downloads") as RapidgatorBlock.Wait).seconds
        )
        assertEquals(3600, (RapidgatorFreePage.classify("You have reached your hourly downloads limit") as RapidgatorBlock.Wait).seconds)
        assertEquals(60, (RapidgatorFreePage.classify("You can`t download not more than 1 file at a time in free mode") as RapidgatorBlock.Wait).seconds)
        assertEquals(60, (RapidgatorFreePage.classify("You can`t download more than 1 file at a time in free mode.") as RapidgatorBlock.Wait).seconds)
        assertEquals(60, (RapidgatorFreePage.classify("File is already downloading") as RapidgatorBlock.Wait).seconds)
        assertEquals(300, (RapidgatorFreePage.classify("File is temporarily not available, please try again later") as RapidgatorBlock.Wait).seconds)
        assertEquals(1800, (RapidgatorFreePage.classify("Downloading is not possible at the moment") as RapidgatorBlock.Wait).seconds)
        assertNull(RapidgatorFreePage.classify("Please hold, download will start in 180 seconds"))
    }

    @Test
    fun sperrenNurImSichtbarenText() {
        // The page script contains texts that are not blocks
        val html = seite.replace("var is_premium = 0;", "var msg = 'You have reached your daily downloads limit';")
        assertNull(RapidgatorFreePage.pageBlock(html))
        val sichtbar = seite.replace("SLOW SPEED DOWNLOAD", "Delay between downloads must be not less than 120 min")
        assertTrue(RapidgatorFreePage.pageBlock(sichtbar) is RapidgatorBlock.Wait)
    }

    @Test
    fun offlineSeitenErkannt() {
        assertTrue(RapidgatorFreePage.isOffline("<div class=\"error\">404 File not found</div>"))
        assertTrue(RapidgatorFreePage.isOffline("<h1>Error 404</h1>"))
        assertTrue(RapidgatorFreePage.isOffline("<td> File not found </td>"))
        assertFalse(RapidgatorFreePage.isOffline("<title>Download file c20-fundamentals.part02.rar</title>"))
    }

    @Test
    fun fileserverAdressenErkannt() {
        assertTrue(hoster.isDirectDownloadUrl("https://pr5.rapidgator.net//?r=download/index&session_id=AbC123"))
        assertTrue(hoster.isDirectDownloadUrl("http://pr_srv.rapidgator.net//?r=download/index&session_id=AbC123"))
        assertTrue(hoster.isDirectDownloadUrl("https://pr12.rapidgator.net/?r=download/index&session_id=x&foo=1"))
        assertTrue(hoster.isDirectDownloadUrl("https://s3.rapidgator.net/dl/abc/video.mkv"))
        // Main-domain pages and URLs without a session id are not the file
        assertFalse(hoster.isDirectDownloadUrl("https://rapidgator.net/?r=download/index&session_id=x"))
        assertFalse(hoster.isDirectDownloadUrl("https://rapidgator.net/download/captcha"))
        assertFalse(hoster.isDirectDownloadUrl("https://rapidgator.net/file/0d348b3c239fe48ea3fed28b8810190d/x.rar.html"))
        assertFalse(hoster.isDirectDownloadUrl("https://pr5.rapidgator.net/?r=download/index"))
        assertFalse(hoster.isDirectDownloadUrl("https://challenges.cloudflare.com/turnstile/v0/api.js"))
    }

    @Test
    fun direktlinkAusDemBrowserWirdMitCookiesUebernommen() = runBlocking {
        Http.browserUserAgent = "Mozilla/5.0 (Test) WebView"
        try {
            val direct = "https://pr5.rapidgator.net//?r=download/index&session_id=AbC123"
            val link = hoster.resolveFree(
                "https://rapidgator.net/file/0d348b3c239fe48ea3fed28b8810190d/c20-fundamentals.part02.rar.html",
                FreeHints(direktUrlAusBrowser = direct, cookies = "sdata__=abc; lang=en")
            )
            assertEquals(direct, link.directUrl)
            assertEquals("sdata__=abc; lang=en", link.headers["Cookie"])
            assertEquals("Mozilla/5.0 (Test) WebView", link.headers["User-Agent"])
            assertEquals("https://rapidgator.net/download/captcha", link.headers["Referer"])
            // Without an in-process session (restart) the server decides the name
            assertNull(link.fileName)

            val ohneCookies = hoster.resolveFree("https://rg.to/file/0d348b3c239fe48ea3fed28b8810190d", FreeHints(direct))
            assertNull(ohneCookies.headers["Cookie"])
            assertNotNull(ohneCookies.headers["User-Agent"])

            // Foreign host: the link counts, the session cookies do not travel
            val fremd = hoster.resolveFree(
                "https://rapidgator.net/file/0d348b3c239fe48ea3fed28b8810190d",
                FreeHints(direktUrlAusBrowser = "https://cdn.example.net/d/abc/name.rar", cookies = "sdata__=abc")
            )
            assertEquals("https://cdn.example.net/d/abc/name.rar", fremd.directUrl)
            assertNull(fremd.headers["Cookie"])
        } finally {
            Http.browserUserAgent = null
        }
    }

    @Test
    fun ungueltigerLinkIstPermanent() = runBlocking {
        try {
            hoster.resolveFree("https://rapidgator.net/article/premium", FreeHints())
            throw AssertionError("Ausnahme erwartet")
        } catch (e: com.jdandroid.hoster.HosterException) {
            assertTrue(e.permanent)
        }
    }
}
