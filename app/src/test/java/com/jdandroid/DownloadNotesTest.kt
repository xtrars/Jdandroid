package com.jdandroid

import com.jdandroid.core.FreeMode
import com.jdandroid.data.ArchiveSets
import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.DownloadQueries
import com.jdandroid.data.DownloadStatus
import com.jdandroid.engine.ArchiveCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Note codes are persisted in downloads.errorMessage and compared by SQL, so
 * their values must never change; the engine writes codes, never texts.
 */
class DownloadNotesTest : SchemaDbTest() {

    @Test
    fun `Codes bleiben stabil`() {
        assertEquals("WAITING_PARTS", DownloadNotes.WAITING_PARTS)
        assertEquals("WAITING_WIFI", DownloadNotes.WAITING_WIFI)
        assertEquals("FREE_WAIT", FreeMode.WAIT_CODE)
        assertEquals("FREE_CAPTCHA", FreeMode.CAPTCHA_CODE)
        assertEquals("Warte auf weitere Archiv-Teile", DownloadNotes.LEGACY_WAITING_PARTS)
        assertEquals("Wartet auf WLAN", DownloadNotes.LEGACY_WAITING_WIFI)
        assertEquals(DownloadNotes.WAITING_PARTS, ArchiveCoordinator.WAITING_NOTE)
    }

    @Test
    fun `requeueIfRunning schreibt den WLAN-Code, keinen Text`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        execute("UPDATE downloads SET speedBps = 500, retryAt = 123 WHERE id = 1")
        execute(DownloadQueries.REQUEUE_IF_RUNNING, "id" to 1L)
        assertEquals(DownloadStatus.QUEUED.name, column(1, "status"))
        assertEquals(DownloadNotes.WAITING_WIFI, column(1, "errorMessage"))
        assertEquals("0", column(1, "retryAt"))
        assertEquals("0", column(1, "speedBps"))
    }

    @Test
    fun `requeueIfRunning laesst alles ausser RUNNING unberuehrt`() {
        item(1, "a.rar", DownloadStatus.COMPLETED, note = DownloadNotes.WAITING_PARTS)
        item(2, "b.rar", DownloadStatus.EXTRACTING)
        item(3, "c.rar", DownloadStatus.PAUSED, note = "Fehler – Versuch 2/5 in 20s")
        item(4, "d.rar", DownloadStatus.QUEUED, note = FreeMode.waitNote())
        for (id in 1L..4L) execute(DownloadQueries.REQUEUE_IF_RUNNING, "id" to id)
        assertEquals(DownloadStatus.COMPLETED.name, column(1, "status"))
        assertEquals(DownloadNotes.WAITING_PARTS, column(1, "errorMessage"))
        assertEquals(DownloadStatus.EXTRACTING.name, column(2, "status"))
        assertNull(column(2, "errorMessage"))
        assertEquals("Fehler – Versuch 2/5 in 20s", column(3, "errorMessage"))
        assertEquals(FreeMode.waitNote(), column(4, "errorMessage"))
    }

    @Test
    fun `Warteabfrage findet nur den Code, nicht den alten Text`() {
        item(1, "film.part1.rar", DownloadStatus.COMPLETED, note = DownloadNotes.WAITING_PARTS)
        item(2, "film.part2.rar", DownloadStatus.COMPLETED, note = DownloadNotes.LEGACY_WAITING_PARTS)
        assertEquals(listOf(1L), ids(ArchiveSets.WAITING_PARTS, "packageId" to 1L, "note" to ArchiveCoordinator.WAITING_NOTE))
    }
}
