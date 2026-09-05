package com.jdandroid.nfs

import com.jdandroid.data.NfsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun `relativePath liefert Pfade ohne fuehrenden Schraegstrich, Wurzel leer`() {
        assertEquals("", NfsSettings.relativePath(""))
        assertEquals("", NfsSettings.relativePath("/"))
        assertEquals("a/b", NfsSettings.relativePath("/a//b/"))
        assertEquals("a/b", NfsSettings.relativePath("a\\b"))
        assertEquals("a", NfsSettings.relativePath("./a/."))
        assertThrows(IllegalArgumentException::class.java) { NfsSettings.relativePath("a/../b") }
        assertThrows(IllegalArgumentException::class.java) { NfsSettings.relativePath("..") }
    }

    @Test
    fun `joinPath und parentPath laufen hinein und zurueck`() {
        assertEquals("film", NfsSettings.joinPath("", "film"))
        assertEquals("film/2024", NfsSettings.joinPath("film", "2024"))
        assertEquals("film", NfsSettings.parentPath("film/2024"))
        assertEquals("", NfsSettings.parentPath("film"))
        assertEquals("", NfsSettings.parentPath(""))
        assertThrows(IllegalArgumentException::class.java) { NfsSettings.joinPath("film", "..") }
        assertThrows(IllegalArgumentException::class.java) { NfsSettings.joinPath("film", "a/b") }
        assertThrows(IllegalArgumentException::class.java) { NfsSettings.joinPath("film", " ") }
    }

    @Test
    fun `isValidName lehnt Trenner und Punktnamen ab`() {
        assertTrue(NfsSettings.isValidName("Serien 2024"))
        assertFalse(NfsSettings.isValidName(""))
        assertFalse(NfsSettings.isValidName("."))
        assertFalse(NfsSettings.isValidName(".."))
        assertFalse(NfsSettings.isValidName("a/b"))
        assertFalse(NfsSettings.isValidName("a\\b"))
    }

    @Test
    fun `gewaehlter Browserpfad wird zum Unterordner unter dem Export`() {
        val base = NfsSettings(export = "/volume1/media")
        assertEquals("/volume1/media/film/2024", base.copy(subDir = NfsSettings.joinPath("film", "2024")).rootPath)
        assertEquals("/volume1/media", base.copy(subDir = NfsSettings.parentPath("film")).rootPath)
    }
}
