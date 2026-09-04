package com.jdandroid.engine

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
 */
class SpeedLimiter {

    @Volatile
    var limitBps: Long = 0

    private val mutex = Mutex()
    private var windowStart = System.currentTimeMillis()
    private var windowBytes = 0L

    suspend fun throttle(bytes: Int) {
        val limit = limitBps
        if (limit <= 0) return

        var waitMs = 0L
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (now - windowStart >= WINDOW_MS) {
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
                waitMs = (windowStart + WINDOW_MS - now).coerceAtLeast(0) + (windows - 1) * WINDOW_MS
                windowStart += windows * WINDOW_MS
                windowBytes = over % limit
            }
        }
        if (waitMs > 0) delay(waitMs)
    }

    private companion object {
        const val WINDOW_MS = 1000L
    }
}
