package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.data.AccountRefresher
import com.jdandroid.data.hasPremium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Minute refresh: invalid accounts are not re-queried every minute; a failed
 * credential re-encryption stays visible in the account status.
 */
class AccountRefresherTest {
    private val now = 1_700_000_000_000L

    @Test
    fun gueltigesKontoMitKontingentJedeMinute() {
        val account = Account(hosterId = "rapidgator", valid = true, lastChecked = now - 60_000)
        assertTrue(AccountRefresher.dueForMinuteRefresh(account, now))
    }

    @Test
    fun ungueltigesKontoNichtErneut() {
        val rapidgator = Account(hosterId = "rapidgator", valid = false, lastChecked = now - 60_000)
        val ddownload = Account(hosterId = "ddownload", valid = false, cookies = "enc1:x", lastChecked = now - 60_000)
        assertFalse(AccountRefresher.dueForMinuteRefresh(rapidgator, now))
        assertFalse(AccountRefresher.dueForMinuteRefresh(ddownload, now))
    }

    @Test
    fun nieGeprueftesKontoImmer() {
        assertTrue(AccountRefresher.dueForMinuteRefresh(Account(hosterId = "rapidgator", valid = false, lastChecked = 0), now))
        assertTrue(AccountRefresher.dueForMinuteRefresh(Account(hosterId = "onefichier", valid = false, trafficUnlimited = true, lastChecked = 0), now))
    }

    @Test
    fun ohneLimitNurWennAbgelaufen() {
        val fresh = Account(hosterId = "onefichier", valid = true, trafficUnlimited = true, lastChecked = now - 60_000)
        val stale = fresh.copy(lastChecked = now - AccountRefresher.STALE_MS - 1)
        assertFalse(AccountRefresher.dueForMinuteRefresh(fresh, now))
        assertTrue(AccountRefresher.dueForMinuteRefresh(stale, now))
        assertFalse(AccountRefresher.dueForMinuteRefresh(stale.copy(valid = false), now))
    }

    @Test
    fun verschluesselungsfehlerImStatusSichtbar() {
        assertEquals("Premium", AccountRefresher.statusWithUpgradeError("Premium", null))
        assertNull(AccountRefresher.statusWithUpgradeError(null, null))
        assertEquals("Keystore fehlt", AccountRefresher.statusWithUpgradeError(null, "Keystore fehlt"))
        val status = AccountRefresher.statusWithUpgradeError("Premium", "Keystore fehlt")
        assertEquals("Premium · Keystore fehlt", status)
        assertTrue(Account(hosterId = "ddownload", valid = true, statusText = status).hasPremium(now))
    }
}
