package com.jdandroid

import com.jdandroid.engine.Extractor
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `archiveBase fuer alle Volume-Schemata`() {
        assertEquals("film", Extractor.archiveBase("film.rar"))
        assertEquals("film", Extractor.archiveBase("film.zip"))
        assertEquals("film", Extractor.archiveBase("film.7z"))
        assertEquals("film", Extractor.archiveBase("film.part1.rar"))
        assertEquals("film", Extractor.archiveBase("film.part02.rar"))
        assertEquals("film", Extractor.archiveBase("film.r00"))
        assertEquals("film", Extractor.archiveBase("film.z01"))
        assertEquals("film", Extractor.archiveBase("film.7z.001"))
        assertNull(Extractor.archiveBase("film.mkv"))
        assertNull(Extractor.archiveBase("dokument.pdf"))
    }

    @Test
    fun `isSecondaryVolume unterscheidet erstes und weitere Teile`() {
        assertFalse(Extractor.isSecondaryVolume("film.part1.rar"))
        assertFalse(Extractor.isSecondaryVolume("film.part01.rar"))
        assertTrue(Extractor.isSecondaryVolume("film.part2.rar"))
        assertTrue(Extractor.isSecondaryVolume("film.part10.rar"))
        assertTrue(Extractor.isSecondaryVolume("film.r00"))
        assertTrue(Extractor.isSecondaryVolume("film.z01"))
        assertFalse(Extractor.isSecondaryVolume("film.rar"))
        assertFalse(Extractor.isSecondaryVolume("film.zip"))
        assertFalse(Extractor.isSecondaryVolume("film.7z.001"))
        assertTrue(Extractor.isSecondaryVolume("film.7z.002"))
    }

    @Test
    fun `unverschluesseltes ZIP wird ohne Passwort entpackt`() {
        val content = tmp.newFile("hallo.txt").apply { writeText("Hallo Welt") }
        val zip = File(tmp.root, "test.zip")
        ZipFile(zip).addFile(content)

        val dest = tmp.newFolder("out")
        val used = Extractor.extract(zip, dest, listOf("falsch"))
        assertNull(used)
        assertEquals("Hallo Welt", File(dest, "hallo.txt").readText())
    }

    @Test
    fun `verschluesseltes ZIP findet richtiges Passwort in der Liste`() {
        val content = tmp.newFile("geheim.txt").apply { writeText("streng geheim") }
        val zip = File(tmp.root, "secret.zip")
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
        }
        ZipFile(zip, "richtig".toCharArray()).addFile(content, params)

        val dest = tmp.newFolder("out2")
        val used = Extractor.extract(zip, dest, listOf("falsch1", "richtig", "falsch2"))
        assertEquals("richtig", used)
        assertEquals("streng geheim", File(dest, "geheim.txt").readText())
    }

    @Test(expected = IOException::class)
    fun `verschluesseltes ZIP ohne passendes Passwort schlaegt fehl`() {
        val content = tmp.newFile("x.txt").apply { writeText("x") }
        val zip = File(tmp.root, "locked.zip")
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
        }
        ZipFile(zip, "korrekt".toCharArray()).addFile(content, params)

        Extractor.extract(zip, tmp.newFolder("out3"), listOf("falsch1", "falsch2"))
    }

    @Test
    fun kaputteNamenMitLeerzeichenWerdenRepariert() {
        assertEquals(
            "9d099111.9f5e.4499.9d3a.2bc366e406cf.part1.rar",
            Extractor.repairName("Download 9d099111 9f5e 4499 9d3a 2bc366e406cf part1 rar")
        )
        assertEquals("scn-smps8-S37E02.rar", Extractor.repairName("scn-smps8-S37E02.rar"))
        assertEquals("Film.mkv", Extractor.repairName("Download Film mkv"))
        assertEquals("name.7z.001", Extractor.repairName("name 7z 001"))
        assertEquals("Nur Text ohne Endung", Extractor.repairName("Nur Text ohne Endung"))
        assertEquals(
            "9d099111.9f5e.4499.9d3a.2bc366e406cf",
            Extractor.archiveBase(Extractor.repairName("Download 9d099111 9f5e 4499 9d3a 2bc366e406cf part2 rar"))
        )
    }

    @Test
    fun ausschlussmusterWieImJDownloader() {
        val ex = listOf("*.nfo", "*sample*", "proof/*")
        assertTrue(Extractor.isExcluded("info.NFO", ex))
        assertTrue(Extractor.isExcluded("Serie/Sample/s01e01.mkv", ex))
        assertTrue(Extractor.isExcluded("proof/bild.jpg", ex))
        assertFalse(Extractor.isExcluded("Serie/s01e01.mkv", ex))
        assertFalse(Extractor.isExcluded("film.mkv", emptyList()))
    }

    @Test
    fun ausgeschlosseneDateienWerdenNichtEntpackt() {
        val src = tmp.newFolder("src-ex")
        val film = File(src, "film.mkv").apply { writeText("video") }
        val nfo = File(src, "info.nfo").apply { writeText("text") }
        val zip = File(tmp.root, "ex.zip")
        ZipFile(zip).apply { addFile(film); addFile(nfo) }
        val dest = tmp.newFolder("out-ex")
        Extractor.extract(zip, dest, emptyList(), listOf("*.nfo"))
        assertTrue(File(dest, "film.mkv").exists())
        assertFalse(File(dest, "info.nfo").exists())
    }
}
