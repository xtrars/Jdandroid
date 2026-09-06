package com.jdandroid

import com.jdandroid.ui.SpeedLimitInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Speed-limit field: the decimal separator follows the locale, parsing accepts both. */
class SpeedLimitInputTest {

    @Test
    fun formatNutztDasUebergebeneTrennzeichen() {
        assertEquals("1,5", SpeedLimitInput.format(1.5, ','))
        assertEquals("1.5", SpeedLimitInput.format(1.5, '.'))
        assertEquals("0.25", SpeedLimitInput.format(0.25, '.'))
        assertEquals("10", SpeedLimitInput.format(10.0, ','))
        assertEquals("0", SpeedLimitInput.format(0.0, '.'))
    }

    @Test
    fun eingabeWirdAufDasTrennzeichenDerLocaleGebracht() {
        assertEquals("1.5", SpeedLimitInput.clean("1,5", '.'))
        assertEquals("1,5", SpeedLimitInput.clean("1.5", ','))
        assertEquals("1.55", SpeedLimitInput.clean("1.5.5", '.'))
        assertEquals("12", SpeedLimitInput.clean("1a2 ", ','))
        assertEquals("12345678", SpeedLimitInput.clean("1234567890", '.'))
    }

    @Test
    fun parseAkzeptiertKommaUndPunkt() {
        assertEquals(1.5, SpeedLimitInput.parse("1,5")!!, 0.0)
        assertEquals(1.5, SpeedLimitInput.parse("1.5")!!, 0.0)
        assertEquals(2.0, SpeedLimitInput.parse("2")!!, 0.0)
        assertNull(SpeedLimitInput.parse(""))
        assertNull(SpeedLimitInput.parse("."))
        assertNull(SpeedLimitInput.parse(","))
    }

    @Test
    fun rundreiseBleibtBeimSelbenWert() {
        for (sep in listOf(',', '.')) {
            val text = SpeedLimitInput.format(1.25, sep)
            assertEquals(1.25, SpeedLimitInput.parse(SpeedLimitInput.clean(text, sep))!!, 0.0)
        }
    }
}
