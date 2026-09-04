package com.jdandroid

import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.engine.ArchiveSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSetsTest {

    private fun item(id: Long, name: String?, status: DownloadStatus, packageId: Long? = 1) = DownloadItem(
        id = id, url = "https://h/$id", hosterId = "h", packageId = packageId, fileName = name, status = status
    )

    private val part1 = item(1, "film.part1.rar", DownloadStatus.RUNNING)

    @Test
    fun `wartendes zweites Teil blockiert`() {
        val all = listOf(part1, item(2, "film.part2.rar", DownloadStatus.QUEUED))
        assertTrue(ArchiveSets.pendingParts(all, 1, 1, "film"))
    }

    @Test
    fun `unbenannter Eintrag im selben Paket blockiert`() {
        val all = listOf(part1, item(2, null, DownloadStatus.RUNNING))
        assertTrue(ArchiveSets.pendingParts(all, 1, 1, "film"))
        // Auch pausiert: der Name kommt erst mit dem Aufloesen
        assertTrue(ArchiveSets.pendingParts(listOf(part1, item(2, null, DownloadStatus.PAUSED)), 1, 1, "film"))
    }

    @Test
    fun `anderes Paket blockiert nicht`() {
        val all = listOf(
            part1,
            item(2, "film.part2.rar", DownloadStatus.QUEUED, packageId = 2),
            item(3, null, DownloadStatus.RUNNING, packageId = 2)
        )
        assertFalse(ArchiveSets.pendingParts(all, 1, 1, "film"))
    }

    @Test
    fun `gescheitertes oder fertiges Teil blockiert nicht`() {
        val all = listOf(
            part1,
            item(2, "film.part2.rar", DownloadStatus.FAILED),
            item(3, "film.part3.rar", DownloadStatus.COMPLETED)
        )
        assertFalse(ArchiveSets.pendingParts(all, 1, 1, "film"))
    }

    @Test
    fun `Name mit Leerzeichen statt Punkten zaehlt zum Set`() {
        val all = listOf(part1, item(2, "film part2 rar", DownloadStatus.QUEUED))
        assertTrue(ArchiveSets.pendingParts(all, 1, 1, "film"))
    }

    @Test
    fun `Eintrag selbst und fremdes Archiv zaehlen nicht`() {
        val all = listOf(part1, item(2, "other.part2.rar", DownloadStatus.QUEUED))
        assertFalse(ArchiveSets.pendingParts(all, 1, 1, "film"))
    }

    @Test
    fun `nur ladende Teile fuer das manuelle Entpacken`() {
        val all = listOf(
            item(1, "film.part1.rar", DownloadStatus.COMPLETED),
            item(2, "film.part2.rar", DownloadStatus.EXTRACTING)
        )
        assertTrue(ArchiveSets.pendingParts(all, 1, 1, "film"))
        assertFalse(ArchiveSets.pendingParts(all, 1, 1, "film", ArchiveSets.LOADING))
        assertTrue(
            ArchiveSets.pendingParts(all + item(3, "film.part3.rar", DownloadStatus.RUNNING), 1, 1, "film", ArchiveSets.LOADING)
        )
    }

    @Test
    fun `archiveSetIds ohne laufende Teile und ohne fremde Pakete`() {
        val all = listOf(
            part1,
            item(2, "film.part2.rar", DownloadStatus.COMPLETED),
            item(3, "film.part3.rar", DownloadStatus.RUNNING),
            item(4, "film.part4.rar", DownloadStatus.EXTRACTING),
            item(5, "film part5 rar", DownloadStatus.COMPLETED),
            item(6, "film.part6.rar", DownloadStatus.COMPLETED, packageId = 2),
            item(7, "other.rar", DownloadStatus.COMPLETED)
        )
        // Der ausloesende Eintrag (noch RUNNING) gehoert immer dazu, andere laufende nicht
        assertEquals(listOf(2L, 4L, 5L, 1L), ArchiveSets.archiveSetIds(all, 1, "film"))
        assertEquals(listOf(6L), ArchiveSets.archiveSetIds(all, 6, "film"))
    }
}
