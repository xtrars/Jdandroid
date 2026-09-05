package com.jdandroid

import com.jdandroid.hoster.DdownloadHoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the download form answers without a direct link, the HTTP status
 * decides between a permanent failure and a retry; server errors and
 * throttling must never end in FAILED.
 */
class DdownloadResolveFailureTest {

    private val hoster = DdownloadHoster()

    @Test
    fun serverfehlerUndDrosselungSindVoruebergehend() {
        assertFalse(hoster.resolveFailurePermanent(500, limitReached = false))
        assertFalse(hoster.resolveFailurePermanent(502, limitReached = false))
        assertFalse(hoster.resolveFailurePermanent(599, limitReached = false))
        assertFalse(hoster.resolveFailurePermanent(429, limitReached = false))
    }

    @Test
    fun limitHinweisIstVoruebergehend() {
        assertFalse(hoster.resolveFailurePermanent(200, limitReached = true))
        assertFalse(hoster.resolveFailurePermanent(404, limitReached = true))
    }

    @Test
    fun unerwarteteAntwortOhneLimitIstDauerhaft() {
        assertTrue(hoster.resolveFailurePermanent(200, limitReached = false))
        assertTrue(hoster.resolveFailurePermanent(302, limitReached = false))
        assertTrue(hoster.resolveFailurePermanent(404, limitReached = false))
        assertTrue(hoster.resolveFailurePermanent(499, limitReached = false))
    }
}
