package com.jdandroid

import com.jdandroid.core.ArchiveNames
import com.jdandroid.data.DownloadQueries
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.OnlineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prueft die Abfragen aus [DownloadQueries] gegen eine echte SQLite-Datenbank
 * mit dem exportierten Schema (siehe [SchemaDbTest]).
 */
class DownloadQueriesTest : SchemaDbTest() {

    private fun applyCheck(id: Long, online: Int, note: String?, fileName: String?, fileSize: Long = -1) =
        execute(
            DownloadQueries.APPLY_CHECK,
            "id" to id, "online" to online, "note" to note, "fileName" to fileName,
            "archiveKey" to ArchiveNames.archiveKey(fileName), "fileSize" to fileSize
        )

    /** Room expandiert (:except) zu einer Platzhalterliste; hier stehen die Werte direkt im Text. */
    private fun openDownloadedBytesExcept(except: List<Long>): Long =
        long(DownloadQueries.OPEN_DOWNLOADED_BYTES_EXCEPT.replace(":except", except.joinToString(",")))

    @Test
    fun `applyCheck ohne Namen laesst Name und archiveKey stehen`() {
        item(1, "film.part1.rar", DownloadStatus.COLLECTED)
        applyCheck(1, OnlineState.OFFLINE, "Datei offline", null)
        assertEquals("film.part1.rar", column(1, "fileName"))
        assertEquals("film", column(1, "archiveKey"))
        assertEquals("2", column(1, "online"))
        assertEquals("Datei offline", column(1, "errorMessage"))
        assertEquals("-1", column(1, "fileSize"))
    }

    @Test
    fun `applyCheck mit Namen setzt archiveKey - auch auf NULL fuer ein Nicht-Archiv`() {
        item(1, null, DownloadStatus.COLLECTED)
        applyCheck(1, OnlineState.ONLINE, null, "film.part2.rar", 1234)
        assertEquals("film.part2.rar", column(1, "fileName"))
        assertEquals("film", column(1, "archiveKey"))
        assertEquals("1234", column(1, "fileSize"))
        assertNull(column(1, "errorMessage"))
        // Neuer Name ohne Archiv-Endung: der alte Schluessel darf nicht stehen bleiben
        applyCheck(1, OnlineState.ONLINE, null, "film.mkv")
        assertEquals("film.mkv", column(1, "fileName"))
        assertNull(column(1, "archiveKey"))
        // Groesse -1 laesst die bekannte Groesse stehen
        assertEquals("1234", column(1, "fileSize"))
    }

    @Test
    fun `applyCheck trifft nur Eintraege im Linksammler`() {
        item(1, "film.part1.rar", DownloadStatus.QUEUED)
        assertEquals(0, applyCheck(1, OnlineState.OFFLINE, "Datei offline", null))
        assertEquals("0", column(1, "online"))
        assertNull(column(1, "errorMessage"))
    }

    @Test
    fun `requeuePausedAndFailed trifft nur pausierte und gescheiterte`() {
        item(1, "a.bin", DownloadStatus.PAUSED, note = "pausiert")
        item(2, "b.bin", DownloadStatus.FAILED, note = "Fehler")
        item(3, "c.bin", DownloadStatus.OFFLINE, note = "offline")
        item(4, "d.bin", DownloadStatus.COMPLETED)
        item(5, "e.bin", DownloadStatus.COLLECTED)
        item(6, "f.bin", DownloadStatus.RUNNING)
        execute("UPDATE downloads SET attempts = 3, retryAt = 99 WHERE id = 2")
        assertEquals(2, execute(DownloadQueries.REQUEUE_PAUSED_AND_FAILED))
        assertEquals(listOf(1L, 2L), ids("SELECT id FROM downloads WHERE status = 'QUEUED' ORDER BY id"))
        assertNull(column(1, "errorMessage"))
        assertEquals("0", column(2, "attempts"))
        assertEquals("0", column(2, "retryAt"))
        assertEquals("OFFLINE", column(3, "status"))
        assertEquals("COMPLETED", column(4, "status"))
        assertEquals("COLLECTED", column(5, "status"))
        assertEquals("RUNNING", column(6, "status"))
    }

    @Test
    fun `openDownloadedBytesExcept summiert offene Eintraege ohne die genannten`() {
        item(1, "a.bin", DownloadStatus.RUNNING, downloadedBytes = 100)
        item(2, "b.bin", DownloadStatus.QUEUED, downloadedBytes = 20)
        item(3, "c.bin", DownloadStatus.PAUSED, downloadedBytes = 3)
        item(4, "d.bin", DownloadStatus.COMPLETED, downloadedBytes = 1000)
        item(5, "e.bin", DownloadStatus.EXTRACTING, downloadedBytes = 5000)
        item(6, "f.bin", DownloadStatus.COLLECTED, downloadedBytes = 7)
        // Wie die Engine: -1 als nie vorhandene Kennung, damit die Liste nie leer ist
        assertEquals(123L, openDownloadedBytesExcept(listOf(-1)))
        assertEquals(23L, openDownloadedBytesExcept(listOf(1, -1)))
        assertEquals(0L, openDownloadedBytesExcept(listOf(1, 2, 3)))
    }

    @Test
    fun `openDownloadedBytesExcept ohne offene Eintraege liefert 0`() {
        item(4, "d.bin", DownloadStatus.COMPLETED, downloadedBytes = 1000)
        assertEquals(0L, openDownloadedBytesExcept(listOf(-1)))
    }
}
