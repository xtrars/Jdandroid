package com.jdandroid

import com.jdandroid.core.Clock
import com.jdandroid.engine.SpeedLimiter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic: the clock is advanced by hand and waits are recorded instead of slept. */
class SpeedLimiterTest {

    private class TestClock(var nanos: Long = 0) : Clock {
        override fun nowNanos() = nanos
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000 }
    }

    private class Harness {
        val clock = TestClock()
        val waits = mutableListOf<Long>()
        // Waiting advances the clock as a real delay() would
        val limiter = SpeedLimiter(clock) { ms -> waits += ms; clock.advanceMs(ms) }
    }

    @Test
    fun ohneLimitKeineVerzoegerung() = runBlocking {
        val h = Harness()
        h.limiter.limitBps = 0
        repeat(200) { h.limiter.throttle(1_000_000) }
        assertTrue(h.waits.isEmpty())
    }

    @Test
    fun limitBremstAus() = runBlocking {
        val h = Harness()
        h.limiter.limitBps = 100_000
        // Three times the quota: the first block fills the window (1 s wait),
        // each further one a full window
        repeat(3) { h.limiter.throttle(100_000) }
        assertEquals(listOf(1000L, 1000L, 1000L), h.waits)
    }

    @Test
    fun ueberschussWirdInsNaechsteFensterUebertragen() = runBlocking {
        val h = Harness()
        h.limiter.limitBps = 30_000
        // 64 KiB at 30 KB/s: a bit over two windows, remainder stays counted
        h.limiter.throttle(65_536)
        assertEquals(listOf(2000L), h.waits)
        // Remaining window: 5536 bytes used, the rest fits without waiting
        h.limiter.throttle(30_000 - 5_536 - 1)
        assertEquals(1, h.waits.size)
    }

    @Test
    fun neuesFensterNachAblaufOhneWartezeit() = runBlocking {
        val h = Harness()
        h.limiter.limitBps = 100_000
        h.limiter.throttle(50_000)
        h.clock.advanceMs(1000)
        h.limiter.throttle(50_000)
        assertTrue(h.waits.isEmpty())
    }

    /** Waiting inside the lock would serialize downloads instead of sharing the quota. */
    @Test
    fun parallelerAufrufWirdNichtDurchDasLockBlockiert() = runBlocking {
        val h = Harness()
        h.limiter.limitBps = 50_000
        h.limiter.throttle(50_000) // Kontingent aufgebraucht, Uhr steht am Fensterende
        val results = (1..4).map { async { h.limiter.throttle(10) } }
        results.awaitAll()
        // Four small calls in the new window: no further wait
        assertEquals(listOf(1000L), h.waits)
    }
}
