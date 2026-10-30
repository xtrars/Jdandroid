package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.data.hasPremium
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only a premium account takes the premium path (hoster.resolve); a valid
 * free account downloads in free mode, where the premium path always failed.
 * Premium comes from the hoster's flag or a future expiry, never from the
 * display text.
 */
class AccountPremiumTest {
    private val now = 1_700_000_000_000L

    @Test
    fun gueltigesKontoOhnePremiumIstFree() {
        assertFalse(Account(hosterId = "rapidgator", valid = true, premiumUntil = 0, statusText = "Free").hasPremium(now))
        assertFalse(Account(hosterId = "ddownload", valid = true, premiumUntil = 0, statusText = null).hasPremium(now))
        // Expired premium
        assertFalse(Account(hosterId = "rapidgator", valid = true, premiumUntil = now - 1, premium = true).hasPremium(now))
    }

    @Test
    fun premiumAnDatumOderKennzeichen() {
        assertTrue(Account(hosterId = "rapidgator", valid = true, premiumUntil = now + 1, statusText = "Free").hasPremium(now))
        assertTrue(Account(hosterId = "onefichier", valid = true, premiumUntil = 0, premium = true).hasPremium(now))
        assertTrue(Account(hosterId = "ddownload", valid = true, premiumUntil = 0, premium = true, statusText = "Ultimate").hasPremium(now))
    }

    @Test
    fun anzeigetextEntscheidetNicht() {
        // A fourth hoster calling its tier "VIP", or a translation reordering the status
        assertTrue(Account(hosterId = "neu", valid = true, premiumUntil = 0, premium = true, statusText = "VIP").hasPremium(now))
        assertFalse(Account(hosterId = "neu", valid = true, premiumUntil = 0, premium = false, statusText = "Premium").hasPremium(now))
    }

    @Test
    fun ungueltigesKontoNie() {
        assertFalse(Account(hosterId = "rapidgator", valid = false, premiumUntil = now + 1, premium = true).hasPremium(now))
    }
}
