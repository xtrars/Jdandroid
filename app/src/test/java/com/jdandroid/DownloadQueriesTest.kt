package com.jdandroid

import com.jdandroid.core.ArchiveNames
import com.jdandroid.data.DownloadQueries
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.OnlineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [DownloadQueries] against a real SQLite database with the exported schema (see [SchemaDbTest]). */
class DownloadQueriesTest : SchemaDbTest() {

    private fun applyCheck(id: Long, online: Int, note: String?, fileName: String?, fileSize: Long = -1) =
        execute(
            DownloadQueries.APPLY_CHECK,
            "id" to id, "online" to online, "note" to note, "fileName" to fileName,
            "archiveKey" to ArchiveNames.archiveKey(fileName), "fileSize" to fileSize
        )

    /** Room expands (:except) to a placeholder list; here the values are inlined. */
    private fun openDownloadedBytesExcept(except: List<Long>): Long =
        long(DownloadQueries.OPEN_DOWNLOADED_BYTES_EXCEPT.replace(":except", except.joinToString(",")))

    /** MIN() without rows yields NULL, mapped to null. */
    private fun nextRetryAt(now: Long, horizon: Long): Long? =
        bind(DownloadQueries.NEXT_RETRY_AT, "now" to now, "horizon" to horizon).use { st ->
            st.executeQuery().use { rs -> rs.next(); rs.getObject(1)?.let { (it as Number).toLong() } }
        }

    private fun retryAt(id: Long, at: Long) = execute("UPDATE downloads SET retryAt = :at WHERE id = :id", "at" to at, "id" to id)

    @Test
    fun `nextRetryAt liefert das kleinste kuenftige retryAt bis zum Horizont`() {
        val now = 1_000_000L
        val horizon = now + 30L * 24 * 3600 * 1000
        assertNull(nextRetryAt(now, horizon))
        item(1, "a.rar", DownloadStatus.QUEUED); retryAt(1, now + 3_600_000)   // Free-Wartezeit 1 h
        item(2, "b.rar", DownloadStatus.QUEUED); retryAt(2, now + 20_000)      // Backoff 20 s
        item(3, "c.rar", DownloadStatus.QUEUED); retryAt(3, now - 1)           // faellig: nextQueued() nimmt ihn
        item(4, "d.rar", DownloadStatus.QUEUED); retryAt(4, horizon + 1)       // Captcha: wartet auf den Nutzer
        item(5, "e.rar", DownloadStatus.PAUSED); retryAt(5, now + 1)           // nicht in der Warteschlange
        assertEquals(now + 20_000L, nextRetryAt(now, horizon))
        // After the backoff the wait time remains
        assertEquals(now + 3_600_000L, nextRetryAt(now + 20_000, horizon))
        // Then only the captcha entry beyond the horizon: no timer
        assertNull(nextRetryAt(now + 3_600_000, horizon))
    }

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
        // New name without archive extension: the old key must not remain
        applyCheck(1, OnlineState.ONLINE, null, "film.mkv")
        assertEquals("film.mkv", column(1, "fileName"))
        assertNull(column(1, "archiveKey"))
        // Size -1 keeps the known size
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
        // Like the engine: -1 as a never-existing id so the list is never empty
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
