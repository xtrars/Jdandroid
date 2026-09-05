package com.jdandroid

import com.jdandroid.engine.Extractor
import com.jdandroid.engine.FileTrees
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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

        // No password and "falsch" fail, then success
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
            // expected
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
    fun mehrteiliges7zWirdUeberAlleTeileEntpackt() {
        val whole = File(tmp.root, "whole.7z")
        val a = (1..3000).joinToString(" ") { "zeile$it" }
        val b = "kurz"
        SevenZOutputFile(whole).use { out ->
            for ((name, text) in listOf("a.txt" to a, "b.txt" to b)) {
                out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name })
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
        }
        // Split like 7-Zip's -v switch: raw bytes cut into equal volumes
        val bytes = whole.readBytes()
        whole.delete()
        val dir = tmp.newFolder("vol7z")
        val size = (bytes.size + 2) / 3
        bytes.toList().chunked(size).forEachIndexed { i, chunk ->
            File(dir, "set.7z.%03d".format(i + 1)).writeBytes(chunk.toByteArray())
        }
        assertEquals(3, dir.list()!!.size)

        val dest = tmp.newFolder("out-vol7z")
        assertNull(Extractor.extract(File(dir, "set.7z.001"), dest, emptyList()))
        assertEquals(a, File(dest, "a.txt").readText())
        assertEquals(b, File(dest, "b.txt").readText())
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
            // expected
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

    @Test
    fun flachEntpackenIgnoriertOrdnerUndNummeriertDoppelteNamen() {
        val src = tmp.newFolder("src-flat")
        File(src, "a").mkdirs(); File(src, "b/c").mkdirs()
        File(src, "a/film.mkv").writeText("eins")
        File(src, "b/c/film.mkv").writeText("zwei")
        File(src, "b/info.txt").writeText("drei")
        val zip = File(tmp.root, "flat.zip")
        ZipFile(zip).apply { addFolder(File(src, "a")); addFolder(File(src, "b")) }

        val flat = tmp.newFolder("out-flat")
        Extractor.extract(zip, flat, emptyList(), flat = true)
        val names = flat.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("film (2).mkv", "film.mkv", "info.txt"), names)
        assertFalse(File(flat, "a").exists())

        val nested = tmp.newFolder("out-nested")
        Extractor.extract(zip, nested, emptyList(), flat = false)
        assertTrue(File(nested, "a/film.mkv").exists())
        assertTrue(File(nested, "b/c/film.mkv").exists())
    }

    @Test
    fun zielnameFlachAusPfadMitBackslash() {
        val dir = tmp.newFolder("t")
        val target = Extractor.Destination(dir)
        assertEquals("x.rar", target.fileFor("ordner\\unter\\x.rar", true).name)
        assertEquals("x (2).rar", target.fileFor("anders/x.rar", true).name)
        assertEquals(File(target.dir, "anders/x.rar").path, target.fileFor("anders/x.rar", false).path)
    }

    @Test
    fun zielpfadPrueftLexikalischOhneDateisystemzugriff() {
        val target = Extractor.Destination(tmp.newFolder("lex"))
        assertEquals("b", target.child("a/../b").name)
        for (bad in listOf("../x", "a/../../x", "", ".")) {
            try {
                target.child(bad)
                throw AssertionError("IOException erwartet fuer '$bad'")
            } catch (e: IOException) {
                // expected
            }
        }
    }

    @Test
    fun symlinkImZipWirdWederAngelegtNochVerfolgt() {
        val secret = tmp.newFolder("privat")
        File(secret, "db.sqlite").writeText("geheim")
        val src = tmp.newFolder("src-link")
        File(src, "echt.txt").writeText("inhalt")
        val dirLink = File(src, "ordnerlink")
        Files.createSymbolicLink(dirLink.toPath(), secret.toPath())
        val fileLink = File(src, "dateilink")
        Files.createSymbolicLink(fileLink.toPath(), File(secret, "db.sqlite").toPath())
        val zip = File(tmp.root, "link.zip")
        val linkOnly = ZipParameters().apply { symbolicLinkAction = ZipParameters.SymbolicLinkAction.INCLUDE_LINK_ONLY }
        ZipFile(zip).apply {
            addFile(File(src, "echt.txt"))
            addFile(dirLink, linkOnly)
            addFile(fileLink, linkOnly)
        }
        assertEquals(3, ZipFile(zip).fileHeaders.size)

        val dest = tmp.newFolder("out-link")
        Extractor.extract(zip, dest, emptyList())
        assertEquals("inhalt", File(dest, "echt.txt").readText())
        assertTrue(FileTrees.regularFiles(dest).map { it.name } == listOf("echt.txt"))
        assertEquals(listOf("echt.txt"), dest.list()!!.toList())
        assertFalse(Files.exists(File(dest, "dateilink").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("geheim", File(secret, "db.sqlite").readText())
    }

    @Test
    fun symlinkIm7zWirdUebersprungen() {
        val archive = File(tmp.root, "link.7z")
        SevenZOutputFile(archive).use { out ->
            val file = SevenZArchiveEntry().apply { name = "echt.txt" }
            out.putArchiveEntry(file)
            out.write("inhalt".toByteArray())
            out.closeArchiveEntry()
            val link = SevenZArchiveEntry().apply {
                name = "link"
                hasWindowsAttributes = true
                windowsAttributes = 0x8000 or (0xA1FF shl 16)
            }
            out.putArchiveEntry(link)
            out.write("/data/data/com.jdandroid".toByteArray())
            out.closeArchiveEntry()
        }
        val dest = tmp.newFolder("out-7z-link")
        Extractor.extract(archive, dest, emptyList())
        assertEquals(listOf("echt.txt"), dest.list()!!.toList())
        assertEquals("inhalt", File(dest, "echt.txt").readText())
    }

    @Test
    fun linkAttributeVon7ZipUndRar() {
        assertTrue(Extractor.isLinkAttributes(0x8000 or (0xA1FF shl 16)))
        assertTrue(Extractor.isLinkAttributes(0x400))
        assertFalse(Extractor.isLinkAttributes(0x8000 or (0x81A4 shl 16)))
        assertFalse(Extractor.isLinkAttributes(0xA1FF shl 16))
        assertFalse(Extractor.isLinkAttributes(0x20))
        assertTrue(Extractor.isLinkMode(0xA1FF))
        assertFalse(Extractor.isLinkMode(0x41ED))
    }

    @Test
    fun aufraeumenUndExportFolgenKeinenLinks() {
        val outside = tmp.newFolder("aussen")
        val kept = File(outside, "bleibt.txt").apply { writeText("x") }
        val dir = tmp.newFolder("baum")
        File(dir, "echt.txt").writeText("y")
        Files.createSymbolicLink(File(dir, "ordnerlink").toPath(), outside.toPath())
        Files.createSymbolicLink(File(dir, "dateilink").toPath(), kept.toPath())

        assertEquals(listOf("echt.txt"), FileTrees.regularFiles(dir).map { it.name })

        FileTrees.deleteTree(dir)
        assertFalse(dir.exists())
        assertEquals("x", kept.readText())
    }

    @Test
    fun fehlerOhnePasswortbezugBrichtSofortAb() {
        val zip = File(tmp.root, "kaputt.zip")
        zip.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64))
        try {
            Extractor.extract(zip, tmp.newFolder("out-kaputt"), listOf("a", "b", "c"))
            throw AssertionError("IOException erwartet")
        } catch (e: IOException) {
            assertFalse(e.message, e.message!!.contains("Passwort"))
        }
    }

    @Test
    fun zipSlipMeldetDenEchtenFehlerTrotzPasswortliste() {
        val zip = File(tmp.root, "slip2.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../evil.txt"))
            out.write("boese".toByteArray())
            out.closeEntry()
        }
        try {
            Extractor.extract(zip, tmp.newFolder("slip2-out"), listOf("p1", "p2"))
            throw AssertionError("IOException erwartet")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("../evil.txt"))
            assertFalse(e.message!!.contains("Passwort"))
        }
    }

    @Test
    fun vorhandeneDateiImPaketordnerWirdNichtUeberschrieben() {
        val src = tmp.newFolder("src-dup")
        File(src, "film.mkv").writeText("neu")
        val zip = File(tmp.root, "dup.zip")
        ZipFile(zip).addFile(File(src, "film.mkv"))
        val dest = tmp.newFolder("paket-dup")
        File(dest, "film.mkv").writeText("alt")

        Extractor.extract(zip, dest, emptyList(), flat = true)
        assertEquals("alt", File(dest, "film.mkv").readText())
        assertEquals("neu", File(dest, "film (2).mkv").readText())
        assertEquals(listOf("film (2).mkv", "film.mkv"), dest.list()!!.sorted())
    }

    @Test
    fun rarHeadersEncryptedErkenntRar4UndRar5() {
        fun rar(vararg body: Int) = tmp.newFile().apply {
            writeBytes(body.map { it.toByte() }.toByteArray())
        }
        val rar4Plain = rar(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0xCF, 0x90, 0x73, 0x00, 0x00, 0x0D, 0x00)
        val rar4Locked = rar(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0xCF, 0x90, 0x73, 0x80, 0x00, 0x0D, 0x00)
        val rar5Plain = rar(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00, 0x33, 0x92, 0xB5, 0xE5, 0x0A, 0x01, 0x05, 0x06)
        val rar5Locked = rar(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00, 0x33, 0x92, 0xB5, 0xE5, 0x27, 0x04, 0x00, 0x00)
        assertFalse(Extractor.rarHeadersEncrypted(rar4Plain))
        assertTrue(Extractor.rarHeadersEncrypted(rar4Locked))
        assertFalse(Extractor.rarHeadersEncrypted(rar5Plain))
        assertTrue(Extractor.rarHeadersEncrypted(rar5Locked))
        assertFalse(Extractor.rarHeadersEncrypted(tmp.newFile().apply { writeText("kein rar") }))
    }

    @Test
    fun ausschlussfilterKompiliertMusterEinmal() {
        val filter = Extractor.ExcludeFilter(listOf("*.nfo", "", "proof/*"))
        assertTrue(filter.matches("info.NFO"))
        assertTrue(filter.matches("proof\\bild.jpg"))
        assertFalse(filter.matches("film.mkv"))
        assertFalse(Extractor.ExcludeFilter(emptyList()).matches("info.nfo"))
    }
}
