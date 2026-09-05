package com.jdandroid

import com.jdandroid.core.Clock
import com.jdandroid.engine.SpeedLimiter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministisch: die Uhr wird von Hand vorgestellt, die Wartezeiten werden
 * nur aufgezeichnet (und in der Uhr nachvollzogen) statt wirklich geschlafen.
 */
class SpeedLimiterTest {

    private class TestClock(var nanos: Long = 0) : Clock {
        override fun nowNanos() = nanos
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000 }
    }

    private class Harness {
        val clock = TestClock()
        val waits = mutableListOf<Long>()
        // Warten = Uhr vorstellen, wie es ein echtes delay() taete
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
        // Dreifaches Kontingent: erster Block fuellt das Fenster (1 s warten),
        // die beiden weiteren je ein volles Fenster
        repeat(3) { h.limiter.throttle(100_000) }
        assertEquals(listOf(1000L, 1000L, 1000L), h.waits)
    }

    @Test
    fun ueberschussWirdInsNaechsteFensterUebertragen() = runBlocking {
        val h = Harness()
        h.limiter.limitBps = 30_000
        // 64 KiB bei 30 KB/s: gut zwei Fenster, Rest bleibt angerechnet
        h.limiter.throttle(65_536)
        assertEquals(listOf(2000L), h.waits)
        // Restfenster: 5536 Bytes sind belegt, der Rest passt ohne Wartezeit
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

    /**
     * Sicherung gegen den behobenen Fehler: wurde im Lock gewartet, blockierte
     * ein wartender Download alle anderen und das Limit wirkte als
     * Serialisierung statt als gemeinsames Kontingent.
     */
    @Test
    fun parallelerAufrufWirdNichtDurchDasLockBlockiert() = runBlocking {
        val h = Harness()
        h.limiter.limitBps = 50_000
        h.limiter.throttle(50_000) // Kontingent aufgebraucht, Uhr steht am Fensterende
        val results = (1..4).map { async { h.limiter.throttle(10) } }
        results.awaitAll()
        // Vier kleine Aufrufe im neuen Fenster: keine weitere Wartezeit
        assertEquals(listOf(1000L), h.waits)
    }
}
