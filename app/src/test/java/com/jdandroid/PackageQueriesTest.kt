package com.jdandroid

import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.PackageQueries
import org.junit.Assert.assertEquals
import org.junit.Test

/** [PackageQueries] against a real SQLite database with the exported schema (see [SchemaDbTest]). */
class PackageQueriesTest : SchemaDbTest() {

    @Test
    fun `requeuePackage stellt nur pausierte, fehlgeschlagene und offline Teile des Pakets zurueck`() {
        item(1, "a.rar", DownloadStatus.PAUSED)
        item(2, "b.rar", DownloadStatus.FAILED, note = "Fehler")
        item(3, "c.rar", DownloadStatus.OFFLINE)
        item(4, "d.rar", DownloadStatus.RUNNING)
        item(5, "e.rar", DownloadStatus.COMPLETED)
        item(6, "f.rar", DownloadStatus.COLLECTED)
        item(7, "g.rar", DownloadStatus.PAUSED, packageId = 2)
        execute("UPDATE downloads SET attempts = 3, retryAt = 99 WHERE id = 2")

        execute(PackageQueries.REQUEUE_PACKAGE, "packageId" to 1L)

        assertEquals(listOf(1L, 2L, 3L), ids("SELECT id FROM downloads WHERE status = 'QUEUED' ORDER BY id"))
        assertEquals("RUNNING", column(4, "status"))
        assertEquals("COMPLETED", column(5, "status"))
        assertEquals("COLLECTED", column(6, "status"))
        assertEquals("PAUSED", column(7, "status"))
        assertEquals(null, column(2, "errorMessage"))
        assertEquals("0", column(2, "attempts"))
        assertEquals("0", column(2, "retryAt"))
    }

    @Test
    fun `deleteCollectedInPackage loescht nur gesammelte Teile des Pakets und zaehlt den Rest`() {
        item(1, "a.rar", DownloadStatus.COLLECTED)
        item(2, "b.rar", DownloadStatus.COLLECTED)
        item(3, "c.rar", DownloadStatus.QUEUED)
        item(4, "d.rar", DownloadStatus.COLLECTED, packageId = 2)

        assertEquals(2, execute(PackageQueries.DELETE_COLLECTED_IN_PACKAGE, "packageId" to 1L))
        assertEquals(listOf(3L, 4L), ids("SELECT id FROM downloads ORDER BY id"))
        assertEquals(1, count(PackageQueries.COUNT_NOT_COLLECTED, "packageId" to 1L))

        assertEquals(1, execute(PackageQueries.DELETE_COLLECTED_IN_PACKAGE, "packageId" to 2L))
        assertEquals(0, count(PackageQueries.COUNT_NOT_COLLECTED, "packageId" to 2L))
    }
}
