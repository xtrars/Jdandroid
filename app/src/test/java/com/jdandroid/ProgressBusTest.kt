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
    fun drosselungGiltJeEintragDieAusgabeGemeinsam() {
        ProgressBus.update(1, LiveProgress(100, 10), now = 1_000)
        // Another entry is accepted, but the map is emitted at most every 500 ms
        assertTrue(ProgressBus.update(2, LiveProgress(5, 1), now = 1_100))
        assertEquals(setOf(1L), ProgressBus.state.value.keys)
        assertTrue(ProgressBus.update(3, LiveProgress(7, 2), now = 1_499))
        assertEquals(setOf(1L), ProgressBus.state.value.keys)
        // The next accepted update after the window carries everything pending
        assertTrue(ProgressBus.update(1, LiveProgress(150, 12), now = 1_500))
        assertEquals(
            mapOf(1L to LiveProgress(150, 12), 2L to LiveProgress(5, 1), 3L to LiveProgress(7, 2)),
            ProgressBus.state.value
        )
    }

    @Test
    fun vieleEintraegeErgebenHoechstensZweiAusgabenProSekunde() = runBlocking {
        var emissions = 0
        val collector = launch(Dispatchers.Unconfined) { ProgressBus.state.collect { emissions++ } }
        try {
            // 20 entries, each publishing every 500 ms with its own offset, over 5 s
            for (tick in 0 until 10) for (id in 1L..20L) {
                ProgressBus.update(id, LiveProgress(tick * 100L + id, id), now = 1_000 + tick * 500L + id * 20)
            }
        } finally {
            collector.cancel()
        }
        // Initial empty map plus one per 500 ms window
        assertTrue("emissions=$emissions", emissions <= 1 + 11)
        assertEquals(20, ProgressBus.state.value.size)
    }

    @Test
    fun entfernenVeroeffentlichtAusstehendeWerteDerAnderen() {
        ProgressBus.update(1, LiveProgress(100, 10), now = 1_000)
        ProgressBus.update(2, LiveProgress(5, 1), now = 1_100)
        ProgressBus.remove(1)
        assertEquals(mapOf(2L to LiveProgress(5, 1)), ProgressBus.state.value)
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
