package com.jdandroid.data

import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.hoster.AccountInfo
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks accounts at the hoster (premium status, expiry, remaining traffic)
 * and stores the result. Used by the accounts view and by the download
 * engine after each completed download.
 */
object AccountRefresher {

    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    /** Re-check on opening the accounts view when the data is older than this. */
    const val STALE_MS = 15 * 60_000L

    /** Minimum interval between checks triggered by completed downloads. */
    const val AFTER_DOWNLOAD_MIN_INTERVAL_MS = 3 * 60_000L

    suspend fun check(app: JdApp, accountId: Long) {
        if (!inFlight.add(accountId)) return
        try {
            val dao = app.db.accountDao()
            val (account, upgradeError) = upgradeSecrets(dao, dao.byId(accountId) ?: return)
            val hoster = HosterRegistry.byId(account.hosterId) ?: return
            val result = try {
                Result.success(hoster.checkAccount(account))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            dao.update(
                applyCheckResult(
                    account, result, upgradeError, System.currentTimeMillis(),
                    checkFailedText = app.getString(R.string.service_account_check_failed),
                    temporaryText = { app.getString(R.string.service_account_status_temporary, it) }
                )
            )
        } finally {
            inFlight.remove(accountId)
        }
    }

    /**
     * Account record after a check. Only a permanent failure (wrong password,
     * banned account, undecryptable credentials) invalidates the account; a
     * network outage or Cloudflare must not push all downloads of the hoster
     * into "no premium account".
     */
    internal fun applyCheckResult(
        account: Account,
        result: Result<AccountInfo>,
        upgradeError: String?,
        now: Long,
        checkFailedText: String,
        temporaryText: (String) -> String
    ): Account {
        val info = result.getOrNull()
        if (info != null) {
            return account.copy(
                valid = info.valid,
                premiumUntil = info.premiumUntil,
                trafficLeft = info.trafficLeft,
                trafficTotal = info.trafficTotal,
                trafficUnlimited = info.trafficUnlimited,
                statusText = statusWithUpgradeError(info.statusText, upgradeError),
                lastChecked = now
            )
        }
        val e = result.exceptionOrNull()
        val permanent = (e is HosterException && e.permanent) || e is Secrets.SecretsException
        return account.copy(
            valid = if (permanent) false else account.valid,
            statusText = (e?.message ?: checkFailedText).let {
                if (!permanent && account.valid) temporaryText(it) else it
            },
            lastChecked = now
        )
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
     * Minute timer: only valid or never-checked accounts; an invalid one is
     * queried again only by a manual check. Unlimited accounts (1fichier)
     * only every [STALE_MS], since their state does not change and 1fichier
     * temporarily blocks frequent requests.
     */
    fun dueForMinuteRefresh(account: Account, now: Long): Boolean =
        (account.valid || account.lastChecked == 0L) &&
            (!account.trafficUnlimited || account.lastChecked < now - STALE_MS)

    /** Checks all accounts last checked more than [maxAgeMs] ago. */
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

    /** After a download: refresh traffic of the affected hoster. */
    fun refreshHoster(app: JdApp, hosterId: String) {
        app.appScope.launch {
            val cutoff = System.currentTimeMillis() - AFTER_DOWNLOAD_MIN_INTERVAL_MS
            app.db.accountDao().byHoster(hosterId)
                .filter { it.lastChecked < cutoff }
                .forEach { launch { check(app, it.id) } }
        }
    }
}
