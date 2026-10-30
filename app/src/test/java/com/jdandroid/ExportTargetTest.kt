package com.jdandroid

import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.AppFolderTarget
import com.jdandroid.engine.NasTarget
import com.jdandroid.engine.Placed
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsTarget
import com.jdandroid.nfs.FakeNfsShare
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The app folder as final fallback and the NAS target that keeps files local when the share fails. */
class ExportTargetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val share = FakeNfsShare()
    private val settings = NfsSettings(enabled = true, server = "nas.local", export = "/volume1/media", subDir = "jd")

    private val appDir: File by lazy { tmp.newFolder("downloads") }
    private val appFolder by lazy { AppFolderTarget(appDir) }
    private val nas by lazy { NasTarget(NfsTarget { share.factory }, settings, appFolder) }

    private fun part(name: String, content: String = "data"): File =
        File(appDir, "$name.part").apply { writeText(content) }

    @Test
    fun `App-Ordner benennt die Teildatei um und haengt bei Kollision (2) an`() = runBlocking {
        File(appDir, "film.mkv").writeText("old")
        val placed = appFolder.finish(part("film.mkv"), "film.mkv", null)
        assertEquals(Placed(File(appDir, "film (2).mkv").absolutePath), placed)
        assertEquals("data", File(appDir, "film (2).mkv").readText())
        assertEquals("old", File(appDir, "film.mkv").readText())
    }

    @Test
    fun `Datei im App-Ordner behaelt ihren Namen beim erneuten Ablegen`() = runBlocking {
        val stored = File(appDir, "film (2).mkv").apply { writeText("data") }
        assertEquals(Placed(stored.absolutePath), appFolder.finish(stored, "film.mkv", null))
        assertTrue(stored.isFile)
    }

    @Test
    fun `App-Ordner loescht nur Dateien unterhalb des eigenen Ordners`() = runBlocking {
        val inside = File(appDir, "x.rar").apply { writeText("x") }
        val outside = tmp.newFile("y.rar")
        assertFalse(appFolder.delete(outside.absolutePath))
        assertTrue(outside.isFile)
        assertTrue(appFolder.delete(inside.absolutePath))
        assertFalse(inside.exists())
        assertFalse(appFolder.restore("x.rar", tmp.newFile("dest")))
    }

    @Test
    fun `App-Ordner laesst entpackte Ordner an Ort und Stelle`() = runBlocking {
        val dir = File(appDir, "Paket").apply { mkdirs(); File(this, "a.txt").writeText("a") }
        assertEquals(Placed(dir.absolutePath), appFolder.exportDirectory(dir, "Paket", null, false))
        assertTrue(File(dir, "a.txt").isFile)
    }

    @Test
    fun `NAS laedt hoch und entfernt die lokale Datei`() = runBlocking {
        val temp = part("film.mkv")
        val placed = nas.finish(temp, "film.mkv", null)
        assertEquals(Placed("nfs://nas.local/volume1/media/jd/film.mkv"), placed)
        assertFalse(temp.exists())
        assertEquals("data", String(share.files["film.mkv"]!!))
    }

    @Test
    fun `NAS meldet den Fortschritt mit der Dateigroesse`() = runBlocking {
        val reports = ArrayList<Pair<Long, Long>>()
        nas.finish(part("film.mkv", "12345"), "film.mkv") { done, total -> reports += done to total }
        assertEquals(listOf(5L to 5L), reports)
    }

    @Test
    fun `NAS nicht erreichbar haelt die Datei im App-Ordner fuer den Wiederversuch`() = runBlocking {
        share.failure = NfsFailure.Transient("timeout")
        val placed = nas.finish(part("film.mkv"), "film.mkv", null)
        assertEquals(File(appDir, "film.mkv").absolutePath, placed.path)
        assertTrue(placed.pending)
        assertEquals(DownloadNotes.EXPORT_PENDING, placed.note)
        assertEquals("data", File(appDir, "film.mkv").readText())
    }

    @Test
    fun `NAS lehnt dauerhaft ab, die Datei bleibt lokal mit Fehlertext`() = runBlocking {
        share.failure = NfsFailure.Permanent("access denied")
        val placed = nas.finish(part("film.mkv"), "film.mkv", null)
        assertFalse(placed.pending)
        assertEquals("access denied", placed.error)
        assertTrue(File(placed.path).isFile)
    }

    @Test
    fun `NAS-Ordnerexport mit Ausfall laesst den Ordner lokal`() = runBlocking {
        val dir = File(appDir, "Paket").apply { mkdirs(); File(this, "a.txt").writeText("a") }
        share.failure = NfsFailure.Transient("timeout")
        val placed = nas.exportDirectory(dir, "Paket", null, false)
        assertEquals(Placed(dir.absolutePath, pending = true), placed)
        assertTrue(File(dir, "a.txt").isFile)
    }

    @Test
    fun `NAS loescht und holt zurueck ueber den gespeicherten Namen`() = runBlocking {
        share.files["x (2).rar"] = "rar".toByteArray()
        val dest = File(tmp.root, "back.rar")
        assertTrue(nas.restore("x (2).rar", dest))
        assertEquals("rar", dest.readText())
        assertTrue(nas.delete("nfs://nas.local/volume1/media/jd/x (2).rar"))
        assertNull(share.files["x (2).rar"])
        assertFalse(nas.delete("nfs://nas.local/volume1/media/jd/x (2).rar"))
    }
}
