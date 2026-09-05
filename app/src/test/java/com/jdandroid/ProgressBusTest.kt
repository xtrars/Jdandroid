package com.jdandroid

import com.jdandroid.core.LiveProgress
import com.jdandroid.core.ProgressBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProgressBusTest {

    @Before
    fun reset() = ProgressBus.clear()

    @After
    fun cleanup() = ProgressBus.clear()

    @Test
    fun ersterWertWirdSofortVeroeffentlicht() {
        assertTrue(ProgressBus.update(1, LiveProgress(100, 10), now = 1_000))
        assertEquals(LiveProgress(100, 10), ProgressBus.state.value[1])
    }

    @Test
    fun innerhalbVon500msWirdNichtErneutVeroeffentlicht() {
        ProgressBus.update(1, LiveProgress(100, 10), now = 1_000)
        assertFalse(ProgressBus.update(1, LiveProgress(200, 20), now = 1_400))
        assertEquals(LiveProgress(100, 10), ProgressBus.state.value[1])
        // Exactly 500 ms later it passes again
        assertTrue(ProgressBus.update(1, LiveProgress(300, 30), now = 1_500))
        assertEquals(LiveProgress(300, 30), ProgressBus.state.value[1])
    }

    @Test
    fun drosselungGiltJeEintrag() {
        ProgressBus.update(1, LiveProgress(100, 10), now = 1_000)
        // Another entry is not throttled by the first
        assertTrue(ProgressBus.update(2, LiveProgress(5, 1), now = 1_100))
        assertEquals(2, ProgressBus.state.value.size)
    }

    @Test
    fun entfernenWirktSofortUndHebtDieDrosselungAuf() {
        ProgressBus.update(1, LiveProgress(100, 10), now = 1_000)
        ProgressBus.remove(1)
        assertNull(ProgressBus.state.value[1])
        // After removal the old publication no longer counts
        assertTrue(ProgressBus.update(1, LiveProgress(0, 0), now = 1_001))
    }

    @Test
    fun removeAllEntferntNurDieGenannten() {
        ProgressBus.update(1, LiveProgress(1, 1), now = 1_000)
        ProgressBus.update(2, LiveProgress(2, 2), now = 1_000)
        ProgressBus.update(3, LiveProgress(3, 3), now = 1_000)
        ProgressBus.removeAll(listOf(1, 3))
        assertEquals(setOf(2L), ProgressBus.state.value.keys)
    }

    @Test
    fun unveraenderterWertLoestKeineNeueVeroeffentlichungAus() = runBlocking {
        val received = mutableListOf<Map<Long, LiveProgress>>()
        // Unconfined: the collector runs on the publishing thread
        val collector = launch(Dispatchers.Unconfined) { ProgressBus.state.collect { received.add(it) } }
        try {
            assertTrue(ProgressBus.update(1, LiveProgress(100, 10), now = 1_000))
            // Same value outside throttling: accepted but not re-emitted
            assertTrue(ProgressBus.update(1, LiveProgress(100, 10), now = 2_000))
            assertTrue(ProgressBus.update(1, LiveProgress(200, 10), now = 3_000))
        } finally {
            collector.cancel()
        }
        assertEquals(
            listOf(emptyMap(), mapOf(1L to LiveProgress(100, 10)), mapOf(1L to LiveProgress(200, 10))),
            received
        )
    }

    @Test
    fun gesamtgeschwindigkeitSummiertAlleEintraege() {
        ProgressBus.update(1, LiveProgress(1, 100), now = 1_000)
        ProgressBus.update(2, LiveProgress(1, 250), now = 1_000)
        ProgressBus.update(3, LiveProgress(extractPercent = 50), now = 1_000)
        assertEquals(350L, ProgressBus.totalSpeedBps())
    }
}
