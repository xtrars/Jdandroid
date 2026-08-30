package com.jdandroid

import com.jdandroid.engine.SpeedLimiter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimiterTest {

    @Test
    fun ohneLimitKeineVerzoegerung() = runBlocking {
        val limiter = SpeedLimiter()
        limiter.limitBps = 0
        val start = System.currentTimeMillis()
        repeat(200) { limiter.throttle(1_000_000) }
        assertTrue(System.currentTimeMillis() - start < 300)
    }

    @Test
    fun limitBremstAus() = runBlocking {
        val limiter = SpeedLimiter()
        limiter.limitBps = 100_000
        val start = System.currentTimeMillis()
        // Dreifaches Kontingent -> muss spuerbar warten
        repeat(3) { limiter.throttle(100_000) }
        assertTrue(System.currentTimeMillis() - start >= 900)
    }

    /**
     * Sicherung gegen den behobenen Fehler: wurde im Lock gewartet, blockierte
     * ein wartender Download alle anderen und das Limit wirkte als
     * Serialisierung statt als gemeinsames Kontingent.
     */
    @Test
    fun parallelerAufrufWirdNichtDurchDasLockBlockiert() = runBlocking {
        val limiter = SpeedLimiter()
        limiter.limitBps = 50_000
        limiter.throttle(50_000) // Kontingent aufgebraucht
        val start = System.currentTimeMillis()
        val results = (1..4).map { async { limiter.throttle(10) } }
        results.awaitAll()
        // Vier kleine Aufrufe duerfen sich nicht zu mehreren Sekunden addieren
        assertTrue(System.currentTimeMillis() - start < 1500)
    }
}
