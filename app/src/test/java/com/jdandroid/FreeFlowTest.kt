package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.engine.FreeFlow
import com.jdandroid.engine.FreePath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Premium before free: a premium account always takes the premium path, free
 * mode only fills in where no premium is available.
 */
class FreeFlowTest {
    private val now = 1_700_000_000_000L

    private fun account(valid: Boolean = true, premiumUntil: Long = 0, status: String? = "Free") =
        Account(hosterId = "rapidgator", valid = valid, premiumUntil = premiumUntil, statusText = status)

    @Test
    fun premiumKontoNimmtImmerDenPremiumWeg() {
        val premium = account(premiumUntil = now + 1)
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(premium, freeMode = false, now = now))
        // Free mode on must not push a paying account into the free flow
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(premium, freeMode = true, now = now))
    }

    @Test
    fun abgelaufenesPremiumLaeuftFreeOderMeldetFehlendesPremium() {
        val expired = account(premiumUntil = now - 1, status = "Premium")
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
        val invalid = account(valid = false, premiumUntil = now + 1, status = "Premium")
        assertEquals(FreePath.DISABLED_ERROR, FreeFlow.choosePath(invalid, freeMode = false, now = now))
        assertEquals(FreePath.FREE, FreeFlow.choosePath(invalid, freeMode = true, now = now))
    }

    @Test
    fun premiumAmKontostatusOhneAblaufdatum() {
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(account(status = "Premium"), freeMode = true, now = now))
        assertEquals(FreePath.PREMIUM, FreeFlow.choosePath(account(status = "Ultimate"), freeMode = false, now = now))
        assertEquals(FreePath.NO_PREMIUM_ERROR, FreeFlow.choosePath(account(status = null), freeMode = false, now = now))
    }
}
