package com.jdandroid

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jdandroid.data.NfsSettings
import com.jdandroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings tab draws from one value set; it must carry the stored values, not the defaults. */
class SettingsValuesTest {

    @Test
    fun leererSpeicherLiefertStandardwerte() {
        val s = SettingsRepository.valuesOf(emptyPreferences())
        assertEquals(2, s.maxConcurrent)
        assertEquals(0.0, s.speedLimitMbit, 0.0)
        assertTrue(s.exportToDownloads)
        assertFalse(s.wifiOnly)
        assertFalse(s.autoStartLinks)
        assertTrue(s.freeMode)
        assertNull(s.downloadTreeUri)
        assertEquals("system", s.themeMode)
        assertTrue(s.autoExtract && s.deleteArchiveAfterExtract && s.flatExtract && s.removeLinksAfterExtract)
        assertEquals("", s.passwordList)
        assertEquals("", s.extractExcludeList)
        assertFalse(s.clickNLoadEnabled)
        assertEquals(NfsSettings(), s.nfs)
    }

    @Test
    fun gespeicherteWerteUeberstimmenStandardwerte() {
        val s = SettingsRepository.valuesOf(
            preferencesOf(
                booleanPreferencesKey("wifi_only") to true,
                booleanPreferencesKey("free_mode") to false,
                stringPreferencesKey("theme_mode") to "dark",
                doublePreferencesKey("speed_limit_mbit") to 1.5,
                intPreferencesKey("max_concurrent") to 4,
                stringPreferencesKey("download_tree_uri") to "content://tree/x",
                booleanPreferencesKey("nfs_enabled") to true,
                stringPreferencesKey("nfs_server") to " nas.local ",
                stringPreferencesKey("nfs_export") to "/volume1/dl"
            )
        )
        assertTrue(s.wifiOnly)
        assertFalse(s.freeMode)
        assertEquals("dark", s.themeMode)
        assertEquals(1.5, s.speedLimitMbit, 0.0)
        assertEquals(4, s.maxConcurrent)
        assertEquals("content://tree/x", s.downloadTreeUri)
        assertEquals(NfsSettings(enabled = true, server = "nas.local", export = "/volume1/dl"), s.nfs)
    }

    @Test
    fun leererOrdnerZaehltAlsNichtGewaehlt() {
        val s = SettingsRepository.valuesOf(preferencesOf(stringPreferencesKey("download_tree_uri") to " "))
        assertNull(s.downloadTreeUri)
    }

    @Test
    fun altesKibLimitWirdUmgerechnet() {
        val s = SettingsRepository.valuesOf(preferencesOf(intPreferencesKey("speed_limit_kbps") to 1000))
        assertEquals(SettingsRepository.kbpsToMbit(1000), s.speedLimitMbit, 0.0)
        val both = SettingsRepository.valuesOf(
            preferencesOf(intPreferencesKey("speed_limit_kbps") to 1000, doublePreferencesKey("speed_limit_mbit") to 3.0)
        )
        assertEquals(3.0, both.speedLimitMbit, 0.0)
    }
}
