package com.jdandroid

import com.jdandroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** Speed limit conversions: Mbit/s (1 Mbit = 1 000 000 bit) to bytes and the legacy KiB/s key. */
class SettingsConversionTest {

    @Test
    fun mbitZuBytesProSekunde() {
        assertEquals(125_000L, SettingsRepository.mbitToBytesPerSecond(1.0))
        assertEquals(62_500L, SettingsRepository.mbitToBytesPerSecond(0.5))
        assertEquals(12_500_000L, SettingsRepository.mbitToBytesPerSecond(100.0))
        // Fractions of a byte are dropped, never rounded up
        assertEquals(1_234L, SettingsRepository.mbitToBytesPerSecond(0.009876))
    }

    @Test
    fun nullUndNegativBedeutenUnbegrenzt() {
        assertEquals(0L, SettingsRepository.mbitToBytesPerSecond(0.0))
        assertEquals(0L, SettingsRepository.mbitToBytesPerSecond(-3.0))
        assertEquals(0L, SettingsRepository.mbitToBytesPerSecond(Double.NaN))
    }

    @Test
    fun alterKibProSekundeWertErgibtDieselbeBytegrenze() {
        // 1024 KiB/s = 1 MiB/s = 8.388608 Mbit/s
        assertEquals(8.388608, SettingsRepository.kbpsToMbit(1024), 1e-9)
        assertEquals(0.0, SettingsRepository.kbpsToMbit(0), 0.0)
        listOf(1, 100, 1024, 50_000).forEach { kbps ->
            assertEquals("$kbps KiB/s", kbps * 1024L, SettingsRepository.mbitToBytesPerSecond(SettingsRepository.kbpsToMbit(kbps)))
        }
    }
}
