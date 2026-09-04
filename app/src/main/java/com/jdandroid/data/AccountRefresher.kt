package com.jdandroid.data

import com.jdandroid.JdApp
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
            val account = dao.byId(accountId) ?: return
            val hoster = HosterRegistry.byId(account.hosterId) ?: return
            val updated = try {
                val info = hoster.checkAccount(account)
                account.copy(
                    valid = info.valid,
                    premiumUntil = info.premiumUntil,
                    trafficLeft = info.trafficLeft,
                    trafficTotal = info.trafficTotal,
                    trafficUnlimited = info.trafficUnlimited,
                    statusText = info.statusText,
                    lastChecked = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                // Nur ein endgueltiger Fehler (falsches Passwort, Konto gesperrt)
                // macht das Konto ungueltig. Netzausfall, Cloudflare oder ein
                // API-Schluckauf im Minutentakt duerfen nicht alle Downloads
                // des Hosters in "kein Premium-Konto" laufen lassen.
                val permanent = e is HosterException && e.permanent
                account.copy(
                    valid = if (permanent) false else account.valid,
                    statusText = (e.message ?: "Prüfung fehlgeschlagen") +
                        if (!permanent && account.valid) " (vorübergehend)" else "",
                    lastChecked = System.currentTimeMillis()
                )
            }
            dao.update(updated)
        } finally {
            inFlight.remove(accountId)
        }
    }

    /** Alle Konten, deren letzte Pruefung aelter als [maxAgeMs] ist. */
    fun refreshStale(app: JdApp, maxAgeMs: Long = STALE_MS) {
        app.appScope.launch {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            app.db.accountDao().all()
                .filter { it.lastChecked < cutoff }
                .forEach { launch { check(app, it.id) } }
        }
    }

    /**
     * Minutentakt in der Kontenansicht: Konten mit Kontingent jede Minute,
     * Konten ohne Limit (1fichier) nur alle [STALE_MS] - deren Stand aendert
     * sich nicht, und 1fichier sperrt bei zu vielen Anfragen voruebergehend.
     */
    fun refreshAll(app: JdApp) {
        app.appScope.launch {
            val cutoff = System.currentTimeMillis() - STALE_MS
            app.db.accountDao().all()
                .filter { !it.trafficUnlimited || it.lastChecked < cutoff }
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
