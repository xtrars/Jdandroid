package com.jdandroid.data

import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Prueft Konten beim Hoster (Premium-Status, Ablauf, verbleibender Traffic)
 * und schreibt das Ergebnis in die Datenbank. Genutzt von der Kontenansicht
 * (manuell und beim Oeffnen, wenn die Daten aelter sind) und von der
 * Download-Engine nach jedem fertigen Download, damit der Traffic-Stand
 * aktuell bleibt - wie im JDownloader.
 */
object AccountRefresher {

    /** Laufende Pruefungen, damit dieselbe Konto-Id nicht doppelt angefragt wird. */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    /** Konto beim Oeffnen der Kontenansicht neu pruefen, wenn aelter als das. */
    const val STALE_MS = 15 * 60_000L

    /** Nach einem Download hoechstens so oft nachfragen. */
    const val AFTER_DOWNLOAD_MIN_INTERVAL_MS = 3 * 60_000L

    suspend fun check(app: JdApp, accountId: Long) {
        if (!inFlight.add(accountId)) return
        try {
            val dao = app.db.accountDao()
            val (account, upgradeError) = upgradeSecrets(dao, dao.byId(accountId) ?: return)
            val hoster = HosterRegistry.byId(account.hosterId) ?: return
            val updated = try {
                val info = hoster.checkAccount(account)
                account.copy(
                    valid = info.valid,
                    premiumUntil = info.premiumUntil,
                    trafficLeft = info.trafficLeft,
                    trafficTotal = info.trafficTotal,
                    trafficUnlimited = info.trafficUnlimited,
                    statusText = statusWithUpgradeError(info.statusText, upgradeError),
                    lastChecked = System.currentTimeMillis()
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nur ein endgueltiger Fehler (falsches Passwort, Konto gesperrt,
                // Zugangsdaten nicht mehr entschluesselbar) macht das Konto
                // ungueltig. Netzausfall, Cloudflare oder ein API-Schluckauf im
                // Minutentakt duerfen nicht alle Downloads des Hosters in
                // "kein Premium-Konto" laufen lassen.
                val permanent = (e is HosterException && e.permanent) || e is Secrets.SecretsException
                account.copy(
                    valid = if (permanent) false else account.valid,
                    statusText = (e.message ?: app.getString(R.string.service_account_check_failed)).let {
                        if (!permanent && account.valid) app.getString(R.string.service_account_status_temporary, it)
                        else it
                    },
                    lastChecked = System.currentTimeMillis()
                )
            }
            dao.update(updated)
        } finally {
            inFlight.remove(accountId)
        }
    }

    /**
     * Re-encrypts plaintext credentials from installations before the Keystore
     * encryption. If the Keystore fails, the record stays unchanged (no account
     * is lost) and the error message is returned so it shows in the account status.
     */
    private suspend fun upgradeSecrets(dao: AccountDao, account: Account): Pair<Account, String?> {
        val needs = listOf(account.password, account.apiKey, account.cookies)
            .any { !it.isNullOrEmpty() && !Secrets.isEncrypted(it) }
        if (!needs) return account to null
        return try {
            val upgraded = account.copy(
                password = account.password?.let { if (Secrets.isEncrypted(it)) it else Secrets.encrypt(it) },
                apiKey = account.apiKey?.let { if (Secrets.isEncrypted(it)) it else Secrets.encrypt(it) },
                cookies = account.cookies?.let { if (Secrets.isEncrypted(it)) it else Secrets.encrypt(it) }
            )
            dao.update(upgraded)
            upgraded to null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            account to (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Hoster status first so [Account.hasPremium] still sees the "Premium" prefix. */
    fun statusWithUpgradeError(status: String?, upgradeError: String?): String? =
        if (upgradeError == null) status
        else listOfNotNull(status?.takeIf { it.isNotBlank() }, upgradeError).joinToString(" · ")

    /**
     * Minutentakt: nur gueltige oder noch nie gepruefte Konten; ein dauerhaft
     * ungueltiges Konto (falsches Passwort, abgelaufene Browser-Sitzung) wird
     * erst durch die manuelle Pruefung wieder angefragt. Konten ohne Limit
     * (1fichier) nur alle [STALE_MS] - deren Stand aendert sich nicht, und
     * 1fichier sperrt bei zu vielen Anfragen voruebergehend.
     */
    fun dueForMinuteRefresh(account: Account, now: Long): Boolean =
        (account.valid || account.lastChecked == 0L) &&
            (!account.trafficUnlimited || account.lastChecked < now - STALE_MS)

    /** Alle Konten, deren letzte Pruefung aelter als [maxAgeMs] ist. */
    fun refreshStale(app: JdApp, maxAgeMs: Long = STALE_MS) {
        app.appScope.launch {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            app.db.accountDao().all()
                .filter { it.lastChecked < cutoff }
                .forEach { launch { check(app, it.id) } }
        }
    }

    /** Accounts view minute timer, see [dueForMinuteRefresh]. */
    fun refreshAll(app: JdApp) {
        app.appScope.launch {
            val now = System.currentTimeMillis()
            app.db.accountDao().all()
                .filter { dueForMinuteRefresh(it, now) }
                .forEach { launch { check(app, it.id) } }
        }
    }

    /** Nach einem Download: Traffic des betroffenen Hosters aktualisieren. */
    fun refreshHoster(app: JdApp, hosterId: String) {
        app.appScope.launch {
            val cutoff = System.currentTimeMillis() - AFTER_DOWNLOAD_MIN_INTERVAL_MS
            app.db.accountDao().byHoster(hosterId)
                .filter { it.lastChecked < cutoff }
                .forEach { launch { check(app, it.id) } }
        }
    }
}
