package com.jdandroid

import com.jdandroid.ui.CaptchaRequestAction
import com.jdandroid.ui.captchaRequestAction
import com.jdandroid.ui.isCaptchaHostAllowed
import com.jdandroid.ui.isHosterHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host filter of the captcha view: hoster domains and captcha services only. */
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
        // Form redirect to the hoster file server: capture
        assertEquals(CaptchaRequestAction.CAPTURE, action(isMainFrame = true, isDirectLink = true, hosterHost = true, hostAllowed = true))
        // Sub-resource with a file extension (ad/captcha script loading an .mp4):
        // never capture, or the view closes before the user sees a captcha
        assertEquals(CaptchaRequestAction.LOAD, action(isMainFrame = false, isDirectLink = true, hosterHost = true, hostAllowed = true))
        assertEquals(CaptchaRequestAction.BLOCK, action(isMainFrame = false, isDirectLink = true, hosterHost = false, hostAllowed = false))
        // Ordinary pages and resources: only the host filter decides
        assertEquals(CaptchaRequestAction.LOAD, action(isMainFrame = true, isDirectLink = false, hosterHost = true, hostAllowed = true))
        assertEquals(CaptchaRequestAction.BLOCK, action(isMainFrame = true, isDirectLink = false, hosterHost = false, hostAllowed = false))
        assertEquals(CaptchaRequestAction.LOAD, action(isMainFrame = false, isDirectLink = false, hosterHost = false, hostAllowed = true))
        assertEquals(CaptchaRequestAction.BLOCK, action(isMainFrame = false, isDirectLink = false, hosterHost = false, hostAllowed = false))
    }

    /** Direct link on a foreign host (ad script, popunder): never capture, the hoster cookies would go there. */
    @Test
    fun direktlinkAufFremdhostWirdNieAbgefangen() {
        assertEquals(CaptchaRequestAction.BLOCK, action(isMainFrame = true, isDirectLink = true, hosterHost = false, hostAllowed = false))
        // Captcha service loaded but not a hoster host: no direct link
        assertEquals(CaptchaRequestAction.LOAD, action(isMainFrame = true, isDirectLink = true, hosterHost = false, hostAllowed = true))
    }

    @Test
    fun hosterHostNurEigeneDomainsUndSeitenHost() {
        assertTrue(isHosterHost("s12.ddownload.com", hosts, null))
        assertTrue(isHosterHost("DDL.to", hosts, null))
        assertTrue(isHosterHost("mirror.example.org", hosts, "mirror.example.org"))
        assertFalse(isHosterHost("challenges.cloudflare.com", hosts, null))
        assertFalse(isHosterHost("www.google.com", hosts, null))
        assertFalse(isHosterHost("ads.example", hosts, "ddownload.com"))
        assertFalse(isHosterHost("ddownload.com.evil.org", hosts, null))
        assertFalse(isHosterHost(null, hosts, null))
    }

    private fun action(isMainFrame: Boolean, isDirectLink: Boolean, hosterHost: Boolean, hostAllowed: Boolean) =
        captchaRequestAction(isMainFrame, isDirectLink, hosterHost, hostAllowed)

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
