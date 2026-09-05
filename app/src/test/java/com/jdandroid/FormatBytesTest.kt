package com.jdandroid

import com.jdandroid.core.formatBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Groessenangaben sind 1024-basiert und liegen in der Kernschicht (auch fuer Benachrichtigungen). */
class FormatBytesTest {

    private lateinit var previous: Locale

    /** Das Dezimalzeichen folgt der Sprache; die Erwartungen unten sind deutsch. */
    @Before
    fun deutscheSprache() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun spracheZuruecksetzen() = Locale.setDefault(previous)

    @Test
    fun binaereEinheiten() {
        assertEquals("0,0 B", formatBytes(0))
        assertEquals("1023,0 B", formatBytes(1023))
        assertEquals("1,0 KiB", formatBytes(1024))
        assertEquals("1,5 MiB", formatBytes(1_572_864))
        assertEquals("1,0 GiB", formatBytes(1L shl 30))
        assertEquals("1,0 TiB", formatBytes(1L shl 40))
    }

    @Test
    fun obersteEinheitBleibtTiB() {
        assertEquals("1024,0 TiB", formatBytes(1L shl 50))
    }

    @Test
    fun unbekannteGroesse() {
        assertEquals("?", formatBytes(-1))
    }
}
