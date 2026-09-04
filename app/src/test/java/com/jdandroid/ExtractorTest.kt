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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private fun encryptedZip(name: String, password: String, entry: String = "geheim.txt"): File {
        val content = File(tmp.newFolder(), entry).apply { writeText("streng geheim") }
        val zip = File(tmp.root, name)
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
        }
        ZipFile(zip, password.toCharArray()).addFile(content, params)
        return zip
    }

    @Test
    fun fehlversuchLoeschtKeineFremdenDateienImZielordner() {
        val zip = encryptedZip("set.zip", "richtig")
        val dest = tmp.newFolder("paket")
        val fremd = File(dest, "anderes-archiv.mkv").apply { writeText("bleibt") }
        val fremdOrdner = File(dest, "Unterordner").apply { mkdirs() }
        File(fremdOrdner, "datei.txt").writeText("bleibt auch")

        // Erst ohne Passwort, dann "falsch" schlagen fehl, dann Erfolg
        val used = Extractor.extract(zip, dest, listOf("falsch", "richtig"))
        assertEquals("richtig", used)
        assertEquals("bleibt", fremd.readText())
        assertEquals("bleibt auch", File(fremdOrdner, "datei.txt").readText())
        assertEquals("streng geheim", File(dest, "geheim.txt").readText())
        assertTrue(dest.listFiles()!!.none { it.name.startsWith(".extract-") })
    }

    @Test
    fun endgueltigerFehlschlagLaesstFremdeDateienUndKeinArbeitsverzeichnis() {
        val zip = encryptedZip("locked2.zip", "korrekt")
        val dest = tmp.newFolder("paket2")
        val fremd = File(dest, "fremd.txt").apply { writeText("bleibt") }

        try {
            Extractor.extract(zip, dest, listOf("falsch"))
            throw AssertionError("IOException erwartet")
        } catch (e: IOException) {
            // erwartet
        }
        assertEquals("bleibt", fremd.readText())
        assertEquals(listOf("fremd.txt"), dest.list()!!.toList())
    }

    @Test
    fun erfolgVerschmilztMitBestehendemUnterordner() {
        val src = tmp.newFolder("src-merge")
        val sub = File(src, "Serie").apply { mkdirs() }
        File(sub, "e02.mkv").writeText("zwei")
        val zip = File(tmp.root, "merge.zip")
        ZipFile(zip).addFolder(sub)
        val dest = tmp.newFolder("paket3")
        File(dest, "Serie").mkdirs()
        File(dest, "Serie/e01.mkv").writeText("eins")

        Extractor.extract(zip, dest, emptyList())
        assertEquals("eins", File(dest, "Serie/e01.mkv").readText())
        assertEquals("zwei", File(dest, "Serie/e02.mkv").readText())
    }

    @Test
    fun sniffExtensionErkenntFormateAnhandDerMagicBytes() {
        val rar = tmp.newFile("a.bin").apply {
            writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x00))
        }
        val zip = tmp.newFile("b.bin").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00))
        }
        val sevenZ = tmp.newFile("c.bin").apply {
            writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04))
        }
        val text = tmp.newFile("d.bin").apply { writeText("kein Archiv hier") }
        val short = tmp.newFile("e.bin").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03)) }
        assertEquals("rar", Extractor.sniffExtension(rar))
        assertEquals("zip", Extractor.sniffExtension(zip))
        assertEquals("7z", Extractor.sniffExtension(sevenZ))
        assertNull(Extractor.sniffExtension(text))
        assertNull(Extractor.sniffExtension(short))
        assertNull(Extractor.sniffExtension(File(tmp.root, "gibt-es-nicht.bin")))
    }

    @Test
    fun sevenZVolumesSortiertNumerisch() {
        val dir = tmp.newFolder("vol")
        listOf("010", "002", "001").forEach { File(dir, "set.7z.$it").writeText(it) }
        File(dir, "anderes.7z.001").writeText("x")
        val volumes = Extractor.sevenZVolumes(File(dir, "set.7z.001"))
        assertEquals(listOf("set.7z.001", "set.7z.002", "set.7z.010"), volumes.map { it.name })
        assertEquals(listOf("einzeln.7z"), Extractor.sevenZVolumes(File(dir, "einzeln.7z")).map { it.name })
    }

    @Test
    fun findPrimaryVolumeBevorzugtErstenTeil() {
        val dir = tmp.newFolder("prim")
        listOf("film.part3.rar", "film.part2.rar", "film.part1.rar", "film.mkv").forEach {
            File(dir, it).writeText(it)
        }
        assertEquals("film.part1.rar", Extractor.findPrimaryVolume(dir, "film")?.name)

        val dir7z = tmp.newFolder("prim7z")
        listOf("set.7z.003", "set.7z.002", "set.7z.001").forEach { File(dir7z, it).writeText(it) }
        assertEquals("set.7z.001", Extractor.findPrimaryVolume(dir7z, "set")?.name)

        val dirR = tmp.newFolder("primr")
        listOf("alt.r01", "alt.r00").forEach { File(dirR, it).writeText(it) }
        assertNull(Extractor.findPrimaryVolume(dirR, "alt"))
        assertNull(Extractor.findPrimaryVolume(dir, "unbekannt"))
    }

    @Test
    fun ausschlussmusterMitFragezeichenBackslashUndMetazeichen() {
        assertTrue(Extractor.isExcluded("s01e01.mkv", listOf("s01e0?.mkv")))
        assertFalse(Extractor.isExcluded("s01e10.mkv", listOf("s01e0?.mkv")))
        assertTrue(Extractor.isExcluded("proof\\bild.jpg", listOf("proof/*")))
        assertTrue(Extractor.isExcluded("/proof/bild.jpg", listOf("proof/*")))
        assertTrue(Extractor.isExcluded("film (1).mkv", listOf("film (1).mkv")))
        assertFalse(Extractor.isExcluded("film 1.mkv", listOf("film (1).mkv")))
        assertFalse(Extractor.isExcluded("filmXmkv", listOf("film.mkv")))
        assertFalse(Extractor.isExcluded("film.mkv", listOf("", "  ")))
    }

    @Test
    fun zipSlipEintragWirdAbgewiesen() {
        val zip = File(tmp.root, "slip.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../evil.txt"))
            out.write("boese".toByteArray())
            out.closeEntry()
        }
        val dest = tmp.newFolder("slip-out")
        try {
            Extractor.extract(zip, dest, emptyList())
            throw AssertionError("IOException erwartet")
        } catch (e: IOException) {
            // erwartet
        }
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(dest, "evil.txt").exists())
        assertTrue(dest.walkTopDown().filter { it.isFile }.none())
    }

    @Test
    fun progressListenerMeldetAnfangUndEnde() {
        val src = tmp.newFolder("src-prog")
        val a = File(src, "a.bin").apply { writeBytes(ByteArray(1000)) }
        val b = File(src, "b.bin").apply { writeBytes(ByteArray(500)) }
        val zip = File(tmp.root, "prog.zip")
        ZipFile(zip).apply { addFile(a); addFile(b) }
        val calls = mutableListOf<Pair<Long, Long>>()
        Extractor.extract(zip, tmp.newFolder("prog-out"), emptyList()) { done, total ->
            calls += done to total
        }
        assertEquals(0L to 1500L, calls.first())
        assertEquals(1500L to 1500L, calls.last())
        assertTrue(calls.all { it.second == 1500L })
        assertEquals(calls.map { it.first }, calls.map { it.first }.sorted())
    }
}
