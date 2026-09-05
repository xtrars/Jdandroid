package com.jdandroid

import com.jdandroid.engine.NetworkChangeFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeFilterTest {

    @Test
    fun `gleiche Meldung loest nur einmal aus`() {
        val filter = NetworkChangeFilter()
        assertTrue(filter.onCapabilities("wlan0", notMetered = true, validated = true))
        // Bandwidth/signal updates repeat the same key
        assertFalse(filter.onCapabilities("wlan0", notMetered = true, validated = true))
        assertFalse(filter.onCapabilities("wlan0", notMetered = true, validated = true))
    }

    @Test
    fun `Wechsel von Netz, Volumentarif oder Validierung zaehlt`() {
        val filter = NetworkChangeFilter()
        assertTrue(filter.onCapabilities("wlan0", notMetered = true, validated = false))
        assertTrue(filter.onCapabilities("wlan0", notMetered = true, validated = true))
        assertTrue(filter.onCapabilities("wlan0", notMetered = false, validated = true))
        assertTrue(filter.onCapabilities("rmnet0", notMetered = false, validated = true))
        assertFalse(filter.onCapabilities("rmnet0", notMetered = false, validated = true))
    }

    @Test
    fun `nach onAvailable zaehlt auch die unveraenderte Meldung`() {
        val filter = NetworkChangeFilter()
        assertTrue(filter.onCapabilities("wlan0", notMetered = true, validated = true))
        filter.onAvailable()
        assertTrue(filter.onCapabilities("wlan0", notMetered = true, validated = true))
        assertFalse(filter.onCapabilities("wlan0", notMetered = true, validated = true))
    }
}
