package com.jdandroid

import com.jdandroid.hoster.DdownloadPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the download form answers without a direct link, the HTTP status
 * decides between a permanent failure and a retry; server errors and
 * throttling must never end in FAILED.
 */
class DdownloadResolveFailureTest {

    @Test
    fun serverfehlerUndDrosselungSindVoruebergehend() {
        assertFalse(DdownloadPage.resolveFailurePermanent(500, limitReached = false))
        assertFalse(DdownloadPage.resolveFailurePermanent(502, limitReached = false))
        assertFalse(DdownloadPage.resolveFailurePermanent(599, limitReached = false))
        assertFalse(DdownloadPage.resolveFailurePermanent(429, limitReached = false))
    }

    @Test
    fun limitHinweisIstVoruebergehend() {
        assertFalse(DdownloadPage.resolveFailurePermanent(200, limitReached = true))
        assertFalse(DdownloadPage.resolveFailurePermanent(404, limitReached = true))
    }

    @Test
    fun unerwarteteAntwortOhneLimitIstDauerhaft() {
        assertTrue(DdownloadPage.resolveFailurePermanent(200, limitReached = false))
        assertTrue(DdownloadPage.resolveFailurePermanent(302, limitReached = false))
        assertTrue(DdownloadPage.resolveFailurePermanent(404, limitReached = false))
        assertTrue(DdownloadPage.resolveFailurePermanent(499, limitReached = false))
    }
}
