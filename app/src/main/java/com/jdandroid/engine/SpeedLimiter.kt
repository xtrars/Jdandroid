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
                waitMs = (windowStart + WINDOW_MS - now).coerceAtLeast(0)
                // Fenster vorziehen: nachfolgende Aufrufe warten dadurch nicht
                // erneut auf dasselbe Kontingent.
                windowStart += WINDOW_MS
                windowBytes = 0
            }
        }
        if (waitMs > 0) delay(waitMs)
    }

    private companion object {
        const val WINDOW_MS = 1000L
    }
}
