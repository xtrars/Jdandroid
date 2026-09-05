package com.jdandroid.nfs

import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.Placed
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NfsTargetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val share = FakeNfsShare()
    private val target = NfsTarget { share.factory }
    private val settings = NfsSettings(enabled = true, server = "nas.local", export = "/volume1/media", subDir = "jd")

    private fun local(name: String, content: String = "data"): File =
        File(tmp.root, name).apply { writeText(content) }

    @Test
    fun `Anzeigepfad nennt Server, Export, Unterordner und Namen`() = runBlocking {
        val outcome = target.finish(settings, local("film.mkv"), "film.mkv")
        assertEquals(NfsTarget.Outcome.Done("nfs://nas.local/volume1/media/jd/film.mkv"), outcome)
        assertEquals("data", String(share.files["film.mkv"]!!))
        assertEquals(1, share.closed)
        assertEquals(listOf(settings), share.opened)
    }

    @Test
    fun `vorhandener Name bekommt (2)`() = runBlocking {
        share.files["film.mkv"] = "old".toByteArray()
        share.files["film (2).mkv"] = "old".toByteArray()
        val outcome = target.finish(settings, local("x"), "film.mkv") as NfsTarget.Outcome.Done
        assertEquals("nfs://nas.local/volume1/media/jd/film (3).mkv", outcome.displayPath)
        assertEquals("old", String(share.files["film.mkv"]!!))
    }

    @Test
    fun `Unterordner werden rekursiv hochgeladen und lokal geloescht`() = runBlocking {
        val dir = tmp.newFolder("Paket")
        File(dir, "a.txt").writeText("a")
        File(dir, "sub/deep").mkdirs()
        File(dir, "sub/b.txt").writeText("b")
        File(dir, "sub/deep/c.txt").writeText("c")
        share.files["Paket/a.txt"] = "old".toByteArray()

        val outcome = target.exportDirectory(settings, dir, "Paket")

        assertEquals(NfsTarget.Outcome.Done("nfs://nas.local/volume1/media/jd/Paket"), outcome)
        assertEquals(setOf("Paket/a.txt", "Paket/a (2).txt", "Paket/sub/b.txt", "Paket/sub/deep/c.txt"), share.files.keys)
        assertTrue("Paket/sub/deep" in share.dirs)
        assertFalse(dir.exists())
    }

    @Test
    fun `Abbruch beim Hochladen laesst keine Teildatei zurueck`() = runBlocking {
        share.failUploadAfterBytes = 0
        val file = local("film.mkv")
        val outcome = target.finish(settings, file, "film.mkv")
        assertTrue(outcome is NfsTarget.Outcome.Failed)
        assertTrue((outcome as NfsTarget.Outcome.Failed).failure is NfsFailure.Transient)
        assertTrue(share.files.isEmpty())
        assertTrue(file.exists())
        assertEquals(1, share.closed)
    }

    @Test
    fun `Ordner-Upload bricht ab und behaelt die restlichen Dateien fuer den naechsten Versuch`() = runBlocking {
        val dir = tmp.newFolder("Paket")
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")
        share.failUploadPath = "Paket/b.txt"

        val outcome = target.exportDirectory(settings, dir, "Paket")

        assertTrue(outcome is NfsTarget.Outcome.Failed)
        assertTrue((outcome as NfsTarget.Outcome.Failed).failure is NfsFailure.Transient)
        assertEquals(setOf("Paket/a.txt"), share.files.keys)
        assertFalse(File(dir, "a.txt").exists())
        assertTrue(File(dir, "b.txt").exists())

        // Retry: only the remaining file is uploaded, nothing gets a "(2)"
        share.failUploadPath = null
        assertEquals(NfsTarget.Outcome.Done("nfs://nas.local/volume1/media/jd/Paket"), target.exportDirectory(settings, dir, "Paket"))
        assertEquals(setOf("Paket/a.txt", "Paket/b.txt"), share.files.keys)
        assertFalse(dir.exists())
    }

    @Test
    fun `nicht erreichbar bleibt lokal mit Vermerk, verweigert liefert Fehlertext`() = runBlocking {
        share.failure = NfsFailure.Transient("NAS aus")
        val transient = target.finish(settings, local("a"), "a") as NfsTarget.Outcome.Failed
        val pending = Placed.local("/local/a", transient.failure)
        assertTrue(pending.pending)
        assertNull(pending.error)
        assertEquals(DownloadNotes.EXPORT_PENDING, pending.note)
        assertEquals("/local/a", pending.path)

        share.failure = NfsFailure.Permanent("Zugriff verweigert")
        val permanent = target.exportDirectory(settings, tmp.newFolder("p"), "p") as NfsTarget.Outcome.Failed
        val failed = Placed.local("/local/p", permanent.failure)
        assertFalse(failed.pending)
        assertEquals("Zugriff verweigert", failed.error)
        assertEquals("Zugriff verweigert", failed.note)
        assertNull(Placed("nfs://x/y").note)
    }

    @Test
    fun `restoreExported holt die Datei zurueck oder meldet false`() = runBlocking {
        share.files["film.rar"] = "rar".toByteArray()
        val dest = File(tmp.root, "back.rar")
        assertTrue(target.restoreExported(settings, "film.rar", dest))
        assertEquals("rar", dest.readText())
        assertFalse(target.restoreExported(settings, "missing.rar", File(tmp.root, "none")))
        share.failure = NfsFailure.Transient("NAS aus")
        assertFalse(target.restoreExported(settings, "film.rar", File(tmp.root, "none2")))
    }

    @Test
    fun `Anzeigepfad ohne Unterordner und mit doppelten Schraegstrichen`() {
        val s = NfsSettings(enabled = true, server = " nas ", export = "volume1//media/")
        assertEquals("nfs://nas/volume1/media/a.bin", NfsTarget.displayPath(s, "a.bin"))
    }
}
