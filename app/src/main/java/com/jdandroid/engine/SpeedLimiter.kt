package com.jdandroid.engine

import com.jdandroid.core.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global speed limit across all running downloads: bytes are counted in
 * one-second windows and a caller that exhausts the quota waits for the next
 * window. Waiting happens outside the lock, otherwise one waiting download
 * would block all others. Windows run on the monotonic [clock]; [wait]
 * (default: delay) lets tests avoid real sleeping.
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
                // Carry the excess into following windows: a 64 KiB block at a
                // 30 KiB/s limit spans more than two windows, otherwise the real
                // throughput never drops below one block per window.
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
