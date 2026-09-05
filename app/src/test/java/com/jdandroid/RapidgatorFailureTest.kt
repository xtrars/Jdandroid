package com.jdandroid

import com.jdandroid.hoster.RapidgatorHoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only an expired token (401 outside login) may trigger a new login. Blocks,
 * limits and server errors are temporary but no reason to drop the session
 * (login counter, parallel-session limit).
 */
class RapidgatorFailureTest {

    private val hoster = RapidgatorHoster()

    @Test
    fun abgelaufenerTokenIstTokenExpiredUndNichtPermanent() {
        val e = hoster.failure(401, "Session not exist", loginCall = false)
        assertTrue(e is RapidgatorHoster.TokenExpired)
        assertFalse(e.permanent)
        assertTrue(hoster.failure(401, "", loginCall = false) is RapidgatorHoster.TokenExpired)
    }

    @Test
    fun falschesPasswortBeimLoginIstPermanentUndKeinTokenExpired() {
        val e = hoster.failure(401, "Wrong password", loginCall = true)
        assertFalse(e is RapidgatorHoster.TokenExpired)
        assertTrue(e.permanent)
    }

    @Test
    fun sperrenUndServerfehlerSindVoruebergehendAberKeinTokenExpired() {
        listOf(
            hoster.failure(403, "Denied by IP", loginCall = false),
            hoster.failure(403, "Daily traffic limit exceeded", loginCall = false),
            hoster.failure(500, "", loginCall = false),
            hoster.failure(503, "Service unavailable", loginCall = false)
        ).forEach { e ->
            assertFalse(e.message, e is RapidgatorHoster.TokenExpired)
            assertFalse(e.message, e.permanent)
        }
    }

    @Test
    fun fehlendeDateiUndFehlendesPremiumSindPermanent() {
        assertTrue(hoster.failure(404, "File not found", loginCall = false).permanent)
        assertTrue(hoster.failure(402, "Premium required", loginCall = false).permanent)
    }
}
