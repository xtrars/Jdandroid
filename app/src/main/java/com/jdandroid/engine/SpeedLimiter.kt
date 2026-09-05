package com.jdandroid.engine

import com.jdandroid.core.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Globale Geschwindigkeitsbegrenzung über alle laufenden Downloads.
 * Zählt Bytes in 1-Sekunden-Fenstern; ist das Kontingent aufgebraucht,
 * wartet der aufrufende Download bis zum nächsten Fenster.
 *
 * Wichtig: gewartet wird ausserhalb des Locks. Andernfalls blockiert ein
 * wartender Download alle anderen, wodurch das Limit faktisch zur
 * Serialisierung aller Downloads führen würde.
 *
 * Die Fenster laufen auf der monotonen [clock]; die Wartezeit selbst geht an
 * [wait] (Standard: delay), damit Tests ohne echtes Schlafen auskommen.
 */
class SpeedLimiter(
    private val clock: Clock = Clock.SYSTEM,
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {

    @Volatile
    var limitBps: Long = 0

    private val mutex = Mutex()
    private var windowStart = clock.nowNanos()
    private var windowBytes = 0L

    suspend fun throttle(bytes: Int) {
        val limit = limitBps
        if (limit <= 0) return

        var waitMs = 0L
        mutex.withLock {
            val now = clock.nowNanos()
            if (now - windowStart >= WINDOW_NS) {
                windowStart = now
                windowBytes = 0
            }
            windowBytes += bytes
            if (windowBytes >= limit) {
                // Ueberschuss ins naechste Fenster uebertragen statt verwerfen:
                // ein 64-KiB-Block bei 30 KiB/s Limit belegt gut zwei Fenster.
                // Vorher lag der reale Durchsatz nie unter einem Block pro Fenster.
                val over = windowBytes - limit
                val windows = 1 + over / limit
                val waitNs = (windowStart + WINDOW_NS - now).coerceAtLeast(0) + (windows - 1) * WINDOW_NS
                waitMs = (waitNs + NS_PER_MS - 1) / NS_PER_MS
                windowStart += windows * WINDOW_NS
                windowBytes = over % limit
            }
        }
        if (waitMs > 0) wait(waitMs)
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
        const val WINDOW_NS = 1000L * NS_PER_MS
    }
}
