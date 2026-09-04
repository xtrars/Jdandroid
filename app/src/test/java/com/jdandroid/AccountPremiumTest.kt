package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.data.hasPremium
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Premium-Erkennung eines Kontos: nur ein Premium-Konto nimmt in der Engine
 * den Premium-Weg (hoster.resolve), ein gueltiges Free-Konto laedt im
 * Free-Modus - der Premium-Weg scheiterte dort dauerhaft.
 */
class AccountPremiumTest {
    private val now = 1_700_000_000_000L

    @Test
    fun gueltigesKontoOhnePremiumIstFree() {
        assertFalse(Account(hosterId = "rapidgator", valid = true, premiumUntil = 0, statusText = "Free").hasPremium(now))
        assertFalse(Account(hosterId = "ddownload", valid = true, premiumUntil = 0, statusText = "Free (Downloads nicht möglich)").hasPremium(now))
        assertFalse(Account(hosterId = "ddownload", valid = true, premiumUntil = 0, statusText = null).hasPremium(now))
        // Abgelaufenes Premium
        assertFalse(Account(hosterId = "rapidgator", valid = true, premiumUntil = now - 1, statusText = "Premium").hasPremium(now))
    }

    @Test
    fun premiumAnDatumOderKontostatus() {
        assertTrue(Account(hosterId = "rapidgator", valid = true, premiumUntil = now + 1, statusText = "Free").hasPremium(now))
        assertTrue(Account(hosterId = "onefichier", valid = true, premiumUntil = 0, statusText = "Premium/Access").hasPremium(now))
        assertTrue(Account(hosterId = "ddownload", valid = true, premiumUntil = 0, statusText = "Ultimate").hasPremium(now))
        assertTrue(Account(hosterId = "ddownload", valid = true, premiumUntil = 0, statusText = "Premium · Kontingent nicht lesbar").hasPremium(now))
    }

    @Test
    fun ungueltigesKontoNie() {
        assertFalse(Account(hosterId = "rapidgator", valid = false, premiumUntil = now + 1, statusText = "Premium").hasPremium(now))
    }
}
