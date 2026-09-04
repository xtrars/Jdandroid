package com.jdandroid

import com.jdandroid.core.ArchiveNames
import com.jdandroid.data.ArchiveSets
import com.jdandroid.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueft die SQL-Abfragen fuer Archiv-Sets ([ArchiveSets]) gegen eine echte
 * SQLite-Datenbank mit dem exportierten Room-Schema (siehe [SchemaDbTest]) -
 * dieselben Texte, die [com.jdandroid.data.DownloadDao] verwendet.
 */
class ArchiveSetsTest : SchemaDbTest() {

    private fun pendingActive(packageId: Long?, key: String, selfId: Long) =
        count(ArchiveSets.PENDING_ACTIVE, "packageId" to packageId, "key" to key, "selfId" to selfId) > 0

    private fun pendingLoading(packageId: Long?, key: String, selfId: Long) =
        count(ArchiveSets.PENDING_LOADING, "packageId" to packageId, "key" to key, "selfId" to selfId) > 0

    private fun setIds(packageId: Long?, key: String, selfId: Long) =
        ids(ArchiveSets.SET_IDS, "packageId" to packageId, "key" to key, "selfId" to selfId)

    @Test
    fun `archiveKey aus dem Dateinamen, auch repariert`() {
        assertEquals("film", ArchiveNames.archiveKey("film.part2.rar"))
        assertEquals("film", ArchiveNames.archiveKey("film part2 rar"))
        assertEquals("film", ArchiveNames.archiveKey("Download film part1 rar"))
        assertEquals("set", ArchiveNames.archiveKey("set.7z.001"))
        assertNull(ArchiveNames.archiveKey("film.mkv"))
        assertNull(ArchiveNames.archiveKey("Nur Text ohne Endung"))
        assertNull(ArchiveNames.archiveKey(null))
    }

    @Test
    fun `wartendes zweites Teil blockiert`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        item(2, "film.part2.rar", DownloadStatus.QUEUED)
        assertTrue(pendingActive(1, "film", 1))
    }

    @Test
    fun `unbenannter Eintrag im selben Paket blockiert`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        item(2, null, DownloadStatus.RUNNING)
        assertTrue(pendingActive(1, "film", 1))
        // Auch pausiert: der Name kommt erst mit dem Aufloesen
        bind("UPDATE downloads SET status = 'PAUSED' WHERE id = 2").use { it.executeUpdate() }
        assertTrue(pendingActive(1, "film", 1))
    }

    @Test
    fun `anderes Paket blockiert nicht`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        item(2, "film.part2.rar", DownloadStatus.QUEUED, packageId = 2)
        item(3, null, DownloadStatus.RUNNING, packageId = 2)
        assertFalse(pendingActive(1, "film", 1))
    }

    @Test
    fun `gescheitertes oder fertiges Teil blockiert nicht`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        item(2, "film.part2.rar", DownloadStatus.FAILED)
        item(3, "film.part3.rar", DownloadStatus.COMPLETED)
        assertFalse(pendingActive(1, "film", 1))
    }

    @Test
    fun `Name mit Leerzeichen statt Punkten zaehlt zum Set`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        item(2, "film part2 rar", DownloadStatus.QUEUED)
        assertTrue(pendingActive(1, "film", 1))
    }

    @Test
    fun `Eintrag selbst und fremdes Archiv zaehlen nicht`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        item(2, "other.part2.rar", DownloadStatus.QUEUED)
        assertFalse(pendingActive(1, "film", 1))
    }

    @Test
    fun `nur ladende Teile fuer das manuelle Entpacken`() {
        item(1, "film.part1.rar", DownloadStatus.COMPLETED)
        item(2, "film.part2.rar", DownloadStatus.EXTRACTING)
        assertTrue(pendingActive(1, "film", 1))
        assertFalse(pendingLoading(1, "film", 1))
        item(3, "film.part3.rar", DownloadStatus.RUNNING)
        assertTrue(pendingLoading(1, "film", 1))
    }

    @Test
    fun `Eintraege ohne Paket bilden ein eigenes Set, Unbenannte zaehlen dort nicht`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING, packageId = null)
        item(2, "film.part2.rar", DownloadStatus.QUEUED, packageId = 1)
        item(3, null, DownloadStatus.QUEUED, packageId = null)
        assertFalse(pendingActive(null, "film", 1))
        item(4, "film.part3.rar", DownloadStatus.COMPLETED, packageId = null)
        assertEquals(listOf(1L, 4L), setIds(null, "film", 1))
        assertEquals(listOf(2L), setIds(1, "film", 2))
    }

    @Test
    fun `archiveSetIds ohne laufende Teile und ohne fremde Pakete`() {
        item(1, "film.part1.rar", DownloadStatus.RUNNING)
        item(2, "film.part2.rar", DownloadStatus.COMPLETED)
        item(3, "film.part3.rar", DownloadStatus.RUNNING)
        item(4, "film.part4.rar", DownloadStatus.EXTRACTING)
        item(5, "film part5 rar", DownloadStatus.COMPLETED)
        item(6, "film.part6.rar", DownloadStatus.COMPLETED, packageId = 2)
        item(7, "other.rar", DownloadStatus.COMPLETED)
        // Der ausloesende Eintrag (noch RUNNING) gehoert immer dazu, andere laufende nicht
        assertEquals(listOf(1L, 2L, 4L, 5L), setIds(1, "film", 1))
        assertEquals(listOf(6L), setIds(2, "film", 6))
    }

    @Test
    fun `completedParts liefert nur fertige Teile des Pakets`() {
        item(1, "film.part1.rar", DownloadStatus.COMPLETED)
        item(2, "film part2 rar", DownloadStatus.COMPLETED)
        item(3, "film.part3.rar", DownloadStatus.RUNNING)
        item(4, "film.part4.rar", DownloadStatus.COMPLETED, packageId = 2)
        assertEquals(listOf(1L, 2L), ids(ArchiveSets.COMPLETED_PARTS, "packageId" to 1L, "key" to "film"))
    }

    @Test
    fun `deleteExtractedSet entfernt Ausloeser und fertige Teile desselben Pakets`() {
        item(1, "film.part1.rar", DownloadStatus.EXTRACTING)
        item(2, "film.part2.rar", DownloadStatus.COMPLETED)
        item(3, "film.part3.rar", DownloadStatus.RUNNING)
        item(4, "film.part4.rar", DownloadStatus.COMPLETED, packageId = 2)
        item(5, "other.rar", DownloadStatus.COMPLETED)
        bind(ArchiveSets.DELETE_EXTRACTED, "packageId" to 1L, "key" to "film", "selfId" to 1L).use { it.executeUpdate() }
        assertEquals(listOf(3L, 4L, 5L), ids("SELECT id FROM downloads ORDER BY id"))
    }

    @Test
    fun `nach deleteExtractedSet liefert SET_IDS die Kennungen nicht mehr`() {
        // Die Engine muss die Kennungen des Sets vor dem Entpacken erfassen und
        // am Ende genau diese aus dem ProgressBus entfernen: eine erneute
        // Abfrage nach "Links nach dem Entpacken entfernen" findet nichts mehr,
        // und die Bus-Eintraege blieben fuer immer liegen
        item(1, "film.part1.rar", DownloadStatus.EXTRACTING)
        item(2, "film.part2.rar", DownloadStatus.EXTRACTING)
        assertEquals(listOf(1L, 2L), setIds(1, "film", 1))
        // Wie die Engine: erst completeExtractingSet, dann das Loeschen
        execute("UPDATE downloads SET status = 'COMPLETED' WHERE id IN (1, 2)")
        execute(ArchiveSets.DELETE_EXTRACTED, "packageId" to 1L, "key" to "film", "selfId" to 1L)
        assertEquals(emptyList<Long>(), setIds(1, "film", 1))
        assertEquals(emptyList<Long>(), setIds(1, "film", 2))
    }

    @Test
    fun `waitingParts nur fertige Archive mit Wartehinweis`() {
        item(1, "film.part1.rar", DownloadStatus.COMPLETED, note = "Warte")
        item(2, "film.part2.rar", DownloadStatus.COMPLETED)
        item(3, "readme.nfo", DownloadStatus.COMPLETED, note = "Warte")
        item(4, "film.part3.rar", DownloadStatus.COMPLETED, packageId = 2, note = "Warte")
        assertEquals(listOf(1L), ids(ArchiveSets.WAITING_PARTS, "packageId" to 1L, "note" to "Warte"))
    }

    @Test
    fun `completedArchives ohne Nicht-Archive und ohne laufende`() {
        item(1, "film.part1.rar", DownloadStatus.COMPLETED)
        item(2, "film.mkv", DownloadStatus.COMPLETED)
        item(3, "set.7z.001", DownloadStatus.RUNNING)
        item(4, "x zip", DownloadStatus.COMPLETED)
        assertEquals(listOf(1L, 4L), ids(ArchiveSets.COMPLETED_ARCHIVES, "packageId" to 1L))
    }

    @Test
    fun `countByArchiveKey zaehlt paketuebergreifend`() {
        item(1, "film.part1.rar", DownloadStatus.COMPLETED)
        item(2, "film.part2.rar", DownloadStatus.QUEUED, packageId = 2)
        assertEquals(2, count(ArchiveSets.COUNT_KEY, "key" to "film"))
        assertEquals(0, count(ArchiveSets.COUNT_KEY, "key" to "other"))
    }

    @Test
    fun `sameNameElsewhere nur fertige Dateien anderer Pakete`() {
        item(1, "film.rar", DownloadStatus.RUNNING)
        item(2, "film.rar", DownloadStatus.COMPLETED, packageId = 2)
        assertEquals(1, count(ArchiveSets.SAME_NAME_ELSEWHERE, "fileName" to "film.rar", "packageId" to 1L))
        assertEquals(0, count(ArchiveSets.SAME_NAME_ELSEWHERE, "fileName" to "film.rar", "packageId" to 2L))
        // Ohne Paket: jedes Paket ist "anderes Paket"
        assertEquals(1, count(ArchiveSets.SAME_NAME_ELSEWHERE, "fileName" to "film.rar", "packageId" to null))
        bind("UPDATE downloads SET status = 'QUEUED' WHERE id = 2").use { it.executeUpdate() }
        assertEquals(0, count(ArchiveSets.SAME_NAME_ELSEWHERE, "fileName" to "film.rar", "packageId" to 1L))
    }
}
