package com.jdandroid

import com.jdandroid.engine.DownloadEngine
import com.jdandroid.core.FileNames
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val url = "https://cdn.example.com/dl/abc/My%20File.rar?token=1".toHttpUrl()

    @Test
    fun `filename-Stern wird URL-dekodiert`() {
        assertEquals("My File.rar", FileNames.fromDisposition("attachment; filename*=UTF-8''My%20File.rar"))
    }

    @Test
    fun `rohes filename wird nicht dekodiert`() {
        assertEquals("C++.zip", FileNames.fromDisposition("attachment; filename=\"C++.zip\""))
        assertEquals("100%.rar", FileNames.fromDisposition("attachment; filename=\"100%.rar\""))
    }

    @Test
    fun `filename ohne Anfuehrungszeichen`() {
        assertEquals("a.rar", FileNames.fromDisposition("attachment; filename=a.rar"))
        assertEquals("a.rar", FileNames.fromDisposition("attachment; filename=a.rar; size=5"))
    }

    @Test
    fun `filename-Stern hat Vorrang vor filename`() {
        assertEquals(
            "Ünïcode.rar",
            FileNames.fromDisposition("attachment; filename=\"fallback.rar\"; filename*=UTF-8''%C3%9Cn%C3%AFcode.rar")
        )
    }

    @Test
    fun `ohne Header letztes Pfadsegment der Adresse`() {
        assertNull(FileNames.fromDisposition(null))
        assertNull(FileNames.fromDisposition("inline"))
        assertEquals("My File.rar", FileNames.fromResponse(null, url))
        assertEquals("My File.rar", FileNames.fromResponse("attachment", url))
    }

    @Test
    fun `leerer Name wird zu download bin`() {
        assertEquals("download.bin", FileNames.sanitize(""))
        assertEquals("download.bin", FileNames.sanitize(" ... "))
        assertEquals("download.bin", FileNames.fromResponse(null, "https://example.com/".toHttpUrl()))
        // An empty header name does not count; the URL applies
        assertEquals("My File.rar", FileNames.fromResponse("attachment; filename=\"\"", url))
    }

    @Test
    fun `Pfad-Traversal und verbotene Zeichen`() {
        val clean = FileNames.sanitize("../../x")
        assertFalse(clean.contains('/'))
        assertFalse(clean.startsWith("."))
        assertEquals("_.._x", clean)
        assertEquals("a_b_c_d_e_f_g_h_i.txt", FileNames.sanitize("a/b\\c:d*e?f\"g<h>i.txt"))
        assertEquals("_a_b.txt", FileNames.fromDisposition("attachment; filename=\"../a/b.txt\""))
    }

    @Test
    fun `lange Namen behalten die Endung`() {
        val long = "x".repeat(300) + ".part1.rar"
        val limited = FileNames.limitLength(long)
        assertTrue(limited.endsWith(".rar"))
        assertTrue(limited.toByteArray().size <= 200)
        // Multibyte: byte limit, not character count
        val umlauts = "ü".repeat(150) + ".mkv"
        val limitedUmlauts = FileNames.sanitize(umlauts)
        assertTrue(limitedUmlauts.endsWith(".mkv"))
        assertTrue(limitedUmlauts.toByteArray().size <= 200)
        assertEquals("kurz.rar", FileNames.limitLength("kurz.rar"))
    }

    @Test
    fun `preferName`() {
        assertTrue(FileNames.preferName(null, "x.rar"))
        assertFalse(FileNames.preferName("x.rar", "x.rar"))
        assertTrue(FileNames.preferName("name part1 rar", "name.part1.rar"))
        assertTrue(FileNames.preferName("Seitentitel ohne Endung", "film.rar"))
        assertTrue(FileNames.preferName("film.mkv", "film.rar"))
        assertFalse(FileNames.preferName("film.rar", "film2.rar"))
        assertFalse(FileNames.preferName("film.mkv", "film2.mkv"))
    }

    @Test
    fun `uniqueFile nummeriert vorhandene Dateien`() {
        val dir = tmp.newFolder()
        assertEquals(File(dir, "film.mkv"), FileNames.uniqueFile(dir, "film.mkv"))
        File(dir, "film.mkv").writeText("x")
        assertEquals(File(dir, "film (2).mkv"), FileNames.uniqueFile(dir, "film.mkv"))
        File(dir, "film (2).mkv").writeText("x")
        assertEquals(File(dir, "film (3).mkv"), FileNames.uniqueFile(dir, "film.mkv"))
        File(dir, "README").writeText("x")
        assertEquals(File(dir, "README (2)"), FileNames.uniqueFile(dir, "README"))
    }

    @Test
    fun `uniqueName nummeriert gegen eine Namensmenge`() {
        val taken = mutableSetOf("film.mkv", "film (2).mkv", "README")
        assertEquals("neu.mkv", FileNames.uniqueName("neu.mkv") { it in taken })
        assertEquals("film (3).mkv", FileNames.uniqueName("film.mkv") { it in taken })
        assertEquals("README (2)", FileNames.uniqueName("README") { it in taken })
        taken += "README (2)"
        assertEquals("README (3)", FileNames.uniqueName("README") { it in taken })
    }

    @Test
    fun `backoff waechst exponentiell und ist begrenzt`() {
        assertEquals(10_000L, DownloadEngine.backoffMillis(1))
        assertEquals(20_000L, DownloadEngine.backoffMillis(2))
        assertEquals(40_000L, DownloadEngine.backoffMillis(3))
        assertEquals(160_000L, DownloadEngine.backoffMillis(5))
        assertEquals(300_000L, DownloadEngine.backoffMillis(6))
        assertEquals(300_000L, DownloadEngine.backoffMillis(50))
    }

    @Test
    fun langerNameBehaeltMehrteiligeEndung() {
        val long = "x".repeat(300)
        val rar = FileNames.limitLength("$long.part1.rar")
        assertTrue(rar, rar.endsWith(".part1.rar"))
        assertTrue(rar.toByteArray().size <= 200)
        val sevenZ = FileNames.limitLength("$long.7z.001")
        assertTrue(sevenZ, sevenZ.endsWith(".7z.001"))
        // A plain extension is still trimmed to the last segment only
        assertTrue(FileNames.limitLength("$long.tar.gz").endsWith(".gz"))
    }
}
