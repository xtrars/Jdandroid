package com.jdandroid

import com.jdandroid.data.NfsSettings
import com.jdandroid.ui.SettingsFieldSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Prefilled settings fields follow the store after a backup restore without disturbing typing. */
class SettingsFieldSyncTest {

    @Test
    fun wiederherstellungErsetztVeraltetenFeldtext() {
        assertEquals("5", SettingsFieldSync.maxConcurrent("2", 5))
        assertEquals("1.5", SettingsFieldSync.speedLimit("0", 1.5, '.'))
        assertEquals("nas.local", SettingsFieldSync.text("alt", "nas.local"))
        assertEquals("1026", SettingsFieldSync.id("1000", 1026, NfsSettings.DEFAULT_UID))
    }

    @Test
    fun passenderTextBleibtUnveraendert() {
        val concurrent = "3"
        assertSame(concurrent, SettingsFieldSync.maxConcurrent(concurrent, 3))
        val speed = "1,"
        assertSame(speed, SettingsFieldSync.speedLimit(speed, 1.0))
        val server = "nas.local "
        assertSame(server, SettingsFieldSync.text(server, "nas.local"))
        val uid = ""
        assertSame(uid, SettingsFieldSync.id(uid, NfsSettings.DEFAULT_UID, NfsSettings.DEFAULT_UID))
    }

    @Test
    fun geleerteEingabeWirdNichtSofortNachgefuellt() {
        assertEquals("", SettingsFieldSync.text("", ""))
        assertEquals("7", SettingsFieldSync.id("7", 7, NfsSettings.DEFAULT_GID))
    }
}
