package com.jdandroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sizes on hoster pages are 1024-based; the decimal separator may be a comma. */
class ParseSizeTest {

    @Test
    fun einheitenNachErstemBuchstaben() {
        assertEquals(1536L, parseSize("1,5", "KB"))
        assertEquals(1536L, parseSize("1.5", "Ko"))
        assertEquals(663L * 1024 * 1024 + (0.63 * 1024 * 1024).toLong(), parseSize("663.63", "MB"))
        assertEquals(1L shl 30, parseSize("1", "Go"))
        assertEquals(2L shl 40, parseSize("2", "TB"))
        assertEquals(700L, parseSize("700", "B"))
        assertEquals(700L, parseSize("700", "o"))
    }

    @Test
    fun unlesbareZahlOderLeereEinheit() {
        assertEquals(-1L, parseSize("abc", "GB"))
        assertEquals(-1L, parseSize("", "GB"))
        assertEquals(42L, parseSize("42", ""))
    }
}
