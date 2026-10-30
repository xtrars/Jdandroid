package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.data.preferredValid
import com.jdandroid.engine.FreeFlow
import com.jdandroid.engine.FreePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Premium before free: a premium account always takes the premium path, free
 * mode only fills in where no premium is available.
 */
class FreeFlowTest {
    private val now = 1_700_000_000_000L

    private fun account(valid: Boolean = true, premiumUntil: Long = 0, premium: Boolean = false) =
        Account(hosterId = "rapidgator", valid = valid, premiumUntil = premiumUntil, premium = premium)

    @Test
    fun premiumKontoNimmtImmerDenPremiumWeg() {
        val premium = account(premiumUntil = now + 1)
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(premium, freeMode = false, now = now))
        // Free mode on must not push a paying account into the free flow
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(premium, freeMode = true, now = now))
    }

    @Test
    fun abgelaufenesPremiumLaeuftFreeOderMeldetFehlendesPremium() {
        val expired = account(premiumUntil = now - 1, premium = true)
        assertEquals(FreePath.FREE, FreeFlow.choosePath(expired, freeMode = true, now = now))
        assertEquals(FreePath.NO_PREMIUM_ERROR, FreeFlow.choosePath(expired, freeMode = false, now = now))
    }

    @Test
    fun kontoOhnePremiumBeiAusgeschaltetemFreeModusIstEinFehler() {
        assertEquals(FreePath.NO_PREMIUM_ERROR, FreeFlow.choosePath(account(), freeMode = false, now = now))
        assertEquals(FreePath.FREE, FreeFlow.choosePath(account(), freeMode = true, now = now))
    }

    @Test
    fun ohneKontoEntscheidetNurDerFreeModus() {
        assertEquals(FreePath.DISABLED_ERROR, FreeFlow.choosePath(null, freeMode = false, now = now))
        assertEquals(FreePath.FREE, FreeFlow.choosePath(null, freeMode = true, now = now))
    }

    @Test
    fun ungueltigesKontoZaehltWieKeinKonto() {
        val invalid = account(valid = false, premiumUntil = now + 1, premium = true)
        assertEquals(FreePath.DISABLED_ERROR, FreeFlow.choosePath(invalid, freeMode = false, now = now))
        assertEquals(FreePath.FREE, FreeFlow.choosePath(invalid, freeMode = true, now = now))
    }

    @Test
    fun premiumAmKennzeichenOhneAblaufdatum() {
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(account(premium = true), freeMode = true, now = now))
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(account(premium = true), freeMode = false, now = now))
        assertEquals(FreePath.NO_PREMIUM_ERROR, FreeFlow.choosePath(account(), freeMode = false, now = now))
    }

    @Test
    fun nachpruefungNurFuerUngeprueftesOderGueltigesAltesKonto() {
        val minute = 60_000L
        assertTrue(FreeFlow.needsRecheck(account(valid = false).copy(lastChecked = 0), now))
        assertTrue(FreeFlow.needsRecheck(account().copy(lastChecked = now - 10 * minute), now))
        assertFalse(FreeFlow.needsRecheck(account().copy(lastChecked = now - minute), now))
        // Permanently invalid (wrong password): not hammered on every download
        assertFalse(FreeFlow.needsRecheck(account(valid = false).copy(lastChecked = now - 10 * minute), now))
    }

    @Test
    fun premiumKontoGewinntGegenAelteresGueltigesFreeKonto() {
        val expired = account(premiumUntil = now - 1, premium = true).copy(id = 1)
        val premium = account(premiumUntil = now + 1).copy(id = 2)
        assertEquals(premium, listOf(expired, premium).preferredValid(now))
        assertEquals(premium, listOf(premium, expired).preferredValid(now))
        // Premium from the flag only, without an expiry date
        val byStatus = account(premium = true).copy(id = 1)
        assertEquals(byStatus, listOf(byStatus, account().copy(id = 2)).preferredValid(now))
    }

    @Test
    fun ohnePremiumGiltDasNeuesteGueltigeKonto() {
        val old = account().copy(id = 1)
        val newer = account().copy(id = 2)
        val invalid = account(valid = false, premiumUntil = now + 1).copy(id = 3)
        assertEquals(newer, listOf(old, newer, invalid).preferredValid(now))
        assertEquals(null, listOf(invalid).preferredValid(now))
    }
}
