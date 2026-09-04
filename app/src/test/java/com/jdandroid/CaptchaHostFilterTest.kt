package com.jdandroid

import com.jdandroid.ui.CaptchaRequestAction
import com.jdandroid.ui.captchaRequestAction
import com.jdandroid.ui.isCaptchaHostAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-Filter der Captcha-Ansicht: Hoster-Domains und Captcha-Dienste, sonst nichts. */
class CaptchaHostFilterTest {
    private val hosts = setOf("ddownload.com", "www.ddownload.com", "ddl.to")

    @Test
    fun hosterDomainsUndSubdomainsErlaubt() {
        assertTrue(isCaptchaHostAllowed("ddownload.com", hosts, null))
        assertTrue(isCaptchaHostAllowed("s12.DDownload.com", hosts, null))
        assertTrue(isCaptchaHostAllowed("ddl.to", hosts, null))
    }

    @Test
    fun seitenHostErlaubtAuchWennNichtInDerListe() {
        assertTrue(isCaptchaHostAllowed("mirror.example.org", hosts, "mirror.example.org"))
        assertTrue(isCaptchaHostAllowed("static.mirror.example.org", hosts, "mirror.example.org"))
    }

    @Test
    fun captchaDiensteErlaubt() {
        assertTrue(isCaptchaHostAllowed("challenges.cloudflare.com", hosts, null))
        assertTrue(isCaptchaHostAllowed("www.google.com", hosts, null))
        assertTrue(isCaptchaHostAllowed("www.gstatic.com", hosts, null))
        assertTrue(isCaptchaHostAllowed("www.recaptcha.net", hosts, null))
        assertTrue(isCaptchaHostAllowed("js.hcaptcha.com", hosts, null))
    }

    @Test
    fun nurHauptrahmenNavigationWirdAlsDirektlinkAbgefangen() {
        // Formular-Weiterleitung auf den Fileserver: abfangen, auch wenn der Host nicht in der Liste steht
        assertEquals(CaptchaRequestAction.CAPTURE, captchaRequestAction(isMainFrame = true, isDirectLink = true, hostAllowed = false))
        assertEquals(CaptchaRequestAction.CAPTURE, captchaRequestAction(isMainFrame = true, isDirectLink = true, hostAllowed = true))
        // Unterressource mit Dateiendung (Werbe-/Captcha-Skript laedt eine .mp4): nie abfangen,
        // sonst schliesst die Ansicht, bevor der Nutzer ein Captcha gesehen hat
        assertEquals(CaptchaRequestAction.LOAD, captchaRequestAction(isMainFrame = false, isDirectLink = true, hostAllowed = true))
        assertEquals(CaptchaRequestAction.BLOCK, captchaRequestAction(isMainFrame = false, isDirectLink = true, hostAllowed = false))
        // Normale Seiten und Ressourcen: nur der Host-Filter entscheidet
        assertEquals(CaptchaRequestAction.LOAD, captchaRequestAction(isMainFrame = true, isDirectLink = false, hostAllowed = true))
        assertEquals(CaptchaRequestAction.BLOCK, captchaRequestAction(isMainFrame = true, isDirectLink = false, hostAllowed = false))
        assertEquals(CaptchaRequestAction.LOAD, captchaRequestAction(isMainFrame = false, isDirectLink = false, hostAllowed = true))
        assertEquals(CaptchaRequestAction.BLOCK, captchaRequestAction(isMainFrame = false, isDirectLink = false, hostAllowed = false))
    }

    @Test
    fun fremdeHostsBlockiert() {
        assertFalse(isCaptchaHostAllowed("tracker.example.org", hosts, null))
        assertFalse(isCaptchaHostAllowed("google.com", hosts, null))
        assertFalse(isCaptchaHostAllowed("evil-ddownload.com", hosts, null))
        assertFalse(isCaptchaHostAllowed("ddownload.com.evil.org", hosts, null))
        assertFalse(isCaptchaHostAllowed("cloudflare.com.evil.org", hosts, null))
        assertFalse(isCaptchaHostAllowed(null, hosts, null))
        assertFalse(isCaptchaHostAllowed("", hosts, ""))
    }
}
