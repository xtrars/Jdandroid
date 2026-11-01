package com.jdandroid

import com.jdandroid.hoster.Http
import com.jdandroid.hoster.RapidgatorApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only an expired token (401 outside login) may trigger a new login. Blocks,
 * limits and server errors are temporary but no reason to drop the session
 * (login counter, parallel-session limit).
 */
class RapidgatorFailureTest {

    private val api = RapidgatorApi("https://rapidgator.net/api/v2", Http.client)

    @Test
    fun abgelaufenerTokenIstTokenExpiredUndNichtPermanent() {
        val e = api.failure(401, "Session not exist", loginCall = false)
        assertTrue(e is RapidgatorApi.TokenExpired)
        assertFalse(e.permanent)
        assertTrue(api.failure(401, "", loginCall = false) is RapidgatorApi.TokenExpired)
    }

    @Test
    fun falschesPasswortBeimLoginIstPermanentUndKeinTokenExpired() {
        val e = api.failure(401, "Wrong password", loginCall = true)
        assertFalse(e is RapidgatorApi.TokenExpired)
        assertTrue(e.permanent)
    }

    @Test
    fun sperrenUndServerfehlerSindVoruebergehendAberKeinTokenExpired() {
        listOf(
            api.failure(403, "Denied by IP", loginCall = false),
            api.failure(403, "Daily traffic limit exceeded", loginCall = false),
            api.failure(500, "", loginCall = false),
            api.failure(503, "Service unavailable", loginCall = false)
        ).forEach { e ->
            assertFalse(e.message, e is RapidgatorApi.TokenExpired)
            assertFalse(e.message, e.permanent)
        }
    }

    @Test
    fun fehlendeDateiUndFehlendesPremiumSindPermanent() {
        assertTrue(api.failure(404, "File not found", loginCall = false).permanent)
        assertTrue(api.failure(402, "Premium required", loginCall = false).permanent)
    }
}
