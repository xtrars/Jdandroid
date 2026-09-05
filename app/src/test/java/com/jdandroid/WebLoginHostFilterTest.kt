package com.jdandroid

import com.jdandroid.ui.isWebLoginHostAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host filter of the login browser, shared by navigations
 * (shouldOverrideUrlLoading) and sub-resources (shouldInterceptRequest).
 */
class WebLoginHostFilterTest {
    private val login = "ddownload.com"

    @Test
    fun loginDomainUndSubdomainsErlaubt() {
        assertTrue(isWebLoginHostAllowed("ddownload.com", login))
        assertTrue(isWebLoginHostAllowed("www.ddownload.com", login))
        assertTrue(isWebLoginHostAllowed("WWW.DDownload.COM", login))
    }

    @Test
    fun cloudflareChallengeErlaubt() {
        assertTrue(isWebLoginHostAllowed("challenges.cloudflare.com", login))
        assertTrue(isWebLoginHostAllowed("static.cloudflare.com", login))
    }

    @Test
    fun fremdeHostsBlockiert() {
        assertFalse(isWebLoginHostAllowed("tracker.example.org", login))
        assertFalse(isWebLoginHostAllowed("cdn.googletagmanager.com", login))
        // Suffix trick without a dot separator must not pass
        assertFalse(isWebLoginHostAllowed("evil-ddownload.com", login))
        assertFalse(isWebLoginHostAllowed("ddownload.com.evil.org", login))
        assertFalse(isWebLoginHostAllowed("cloudflare.com.evil.org", login))
    }

    @Test
    fun leereOderFehlendeHostsBlockiert() {
        assertFalse(isWebLoginHostAllowed(null, login))
        assertFalse(isWebLoginHostAllowed("", login))
        assertFalse(isWebLoginHostAllowed("ddownload.com", ""))
    }
}
