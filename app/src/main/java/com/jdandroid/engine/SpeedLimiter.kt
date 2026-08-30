package com.jdandroid.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Globale Geschwindigkeitsbegrenzung ueber alle laufenden Downloads:
 * Zaehlt Bytes pro 1-Sekunden-Fenster und laesst Downloads warten,
 * sobald das Limit erreicht ist. 0 = unbegrenzt.
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
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000) {
                windowStart = now
                windowBytes = 0
            }
            windowBytes += bytes
            if (windowBytes >= limit) {
                val wait = 1000 - (System.currentTimeMillis() - windowStart)
                if (wait > 0) delay(wait)
                windowStart = System.currentTimeMillis()
                windowBytes = 0
            }
        }
    }
}
