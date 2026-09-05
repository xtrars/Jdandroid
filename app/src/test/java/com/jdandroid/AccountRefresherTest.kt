package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.data.AccountRefresher
import com.jdandroid.data.Secrets
import com.jdandroid.data.hasPremium
import com.jdandroid.hoster.AccountInfo
import com.jdandroid.hoster.HosterException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Minute refresh: invalid accounts are not re-queried every minute; a failed
 * credential re-encryption stays visible in the account status. Check
 * results: only permanent failures invalidate an account.
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

    private val premium = Account(
        hosterId = "rapidgator", valid = true, premiumUntil = now + 86_400_000, trafficLeft = 5, trafficTotal = 10,
        statusText = "Premium", lastChecked = now - 60_000
    )

    private fun apply(account: Account, result: Result<AccountInfo>, upgradeError: String? = null) =
        AccountRefresher.applyCheckResult(
            account, result, upgradeError, now,
            checkFailedText = "Prüfung fehlgeschlagen", temporaryText = { "$it (vorübergehend)" }
        )

    @Test
    fun erfolgreichePruefungUebernimmtAlleWerte() {
        val info = AccountInfo(valid = true, premiumUntil = now + 1, trafficLeft = 1, trafficTotal = 2, trafficUnlimited = true, statusText = "Premium")
        val updated = apply(premium.copy(valid = false, statusText = "alt"), Result.success(info))
        assertEquals(premium.copy(valid = true, premiumUntil = now + 1, trafficLeft = 1, trafficTotal = 2, trafficUnlimited = true, statusText = "Premium", lastChecked = now), updated)
        // Hoster says invalid: taken over as is
        assertFalse(apply(premium, Result.success(info.copy(valid = false, statusText = "Gesperrt"))).valid)
        assertEquals("Premium · Keystore fehlt", apply(premium, Result.success(info), "Keystore fehlt").statusText)
    }

    @Test
    fun voruebergehenderFehlerBehaeltGueltigkeitUndKontingent() {
        val updated = apply(premium, Result.failure(HosterException("HTTP 503", permanent = false)))
        assertTrue(updated.valid)
        assertEquals("HTTP 503 (vorübergehend)", updated.statusText)
        assertEquals(premium.trafficLeft, updated.trafficLeft)
        assertEquals(premium.premiumUntil, updated.premiumUntil)
        assertEquals(now, updated.lastChecked)
        // Other exceptions (network) count as temporary too
        assertTrue(apply(premium, Result.failure(java.io.IOException("timeout"))).valid)
        // Without a message the generic text is used
        assertEquals("Prüfung fehlgeschlagen (vorübergehend)", apply(premium, Result.failure(java.io.IOException())).statusText)
    }

    @Test
    fun voruebergehenderFehlerAnUngueltigemKontoOhneZusatz() {
        val invalid = premium.copy(valid = false, statusText = "Passwort falsch")
        val updated = apply(invalid, Result.failure(HosterException("HTTP 503")))
        assertFalse(updated.valid)
        assertEquals("HTTP 503", updated.statusText)
    }

    @Test
    fun dauerhafterFehlerMachtDasKontoUngueltig() {
        val updated = apply(premium, Result.failure(HosterException("Passwort falsch", permanent = true)))
        assertFalse(updated.valid)
        assertEquals("Passwort falsch", updated.statusText)
        assertEquals(now, updated.lastChecked)
        assertFalse(updated.hasPremium(now))
    }

    @Test
    fun unlesbareZugangsdatenMachenDasKontoUngueltig() {
        val e = Secrets.SecretsException("Zugangsdaten nicht lesbar", null)
        val updated = apply(premium, Result.failure(e))
        assertFalse(updated.valid)
        assertEquals("Zugangsdaten nicht lesbar", updated.statusText)
    }
}
