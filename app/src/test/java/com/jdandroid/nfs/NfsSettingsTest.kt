package com.jdandroid.nfs

import com.jdandroid.data.NfsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfsSettingsTest {

    @Test
    fun `normalizePath faltet Schraegstriche und entfernt den letzten`() {
        assertEquals("/", NfsSettings.normalizePath(""))
        assertEquals("/", NfsSettings.normalizePath("///"))
        assertEquals("/a/b", NfsSettings.normalizePath("a//b/"))
        assertEquals("/a/b", NfsSettings.normalizePath("\\a\\b\\"))
        assertEquals("/a/b", NfsSettings.normalizePath("/./a/./b"))
    }

    @Test
    fun `rootPath haengt den Unterordner an den Export`() {
        assertEquals("/volume1/media", NfsSettings(export = "/volume1/media/").rootPath)
        assertEquals("/volume1/media/jd", NfsSettings(export = "volume1/media", subDir = "/jd/").rootPath)
        assertEquals("/volume1/media/a/b", NfsSettings(export = "/volume1/media", subDir = "a\\b").rootPath)
        assertEquals("/", NfsSettings().rootPath)
    }

    @Test
    fun `isUsable verlangt Schalter, Server und Export`() {
        assertFalse(NfsSettings().isUsable)
        assertFalse(NfsSettings(enabled = true, server = "nas").isUsable)
        assertFalse(NfsSettings(enabled = true, export = "/x").isUsable)
        assertFalse(NfsSettings(enabled = false, server = "nas", export = "/x").isUsable)
        assertFalse(NfsSettings(enabled = true, server = " ", export = "/x").isUsable)
        assertTrue(NfsSettings(enabled = true, server = "nas", export = "/x").isUsable)
        assertEquals(1000, NfsSettings().uid)
        assertEquals(1000, NfsSettings().gid)
    }
}
