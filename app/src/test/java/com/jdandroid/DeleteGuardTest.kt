package com.jdandroid

import com.jdandroid.core.LiveProgress
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadPackage
import com.jdandroid.data.DownloadStatus
import com.jdandroid.ui.DeleteBlock
import com.jdandroid.ui.DeleteGuard
import com.jdandroid.ui.groupDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Deleting is blocked only while extracting or moving to the NAS; the move takes precedence. */
class DeleteGuardTest {

    private fun item(id: Long, status: DownloadStatus) =
        DownloadItem(id = id, url = "https://example.test/$id", hosterId = "test", packageId = 1, status = status)

    @Test
    fun eintragNurWaehrendEntpackenOderVerschiebenGesperrt() {
        assertEquals(DeleteBlock.EXTRACTING, DeleteGuard.forItem(item(1, DownloadStatus.EXTRACTING), -1))
        assertEquals(DeleteBlock.UPLOADING, DeleteGuard.forItem(item(1, DownloadStatus.EXTRACTING), 40))
        assertEquals(DeleteBlock.UPLOADING, DeleteGuard.forItem(item(1, DownloadStatus.RUNNING), 0))
        for (status in listOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.PAUSED, DownloadStatus.COMPLETED, DownloadStatus.FAILED)) {
            assertNull(status.name, DeleteGuard.forItem(item(1, status), -1))
        }
    }

    @Test
    fun paketGesperrtSobaldEinTeilBetroffenIst() {
        val packages = listOf(DownloadPackage(id = 1, name = "A"))
        val items = listOf(item(1, DownloadStatus.COMPLETED), item(2, DownloadStatus.EXTRACTING), item(3, DownloadStatus.RUNNING))
        assertEquals(DeleteBlock.EXTRACTING, DeleteGuard.forGroup(groupDownloads(items, packages, "x").single()))
        val uploading = mapOf(1L to LiveProgress(uploadPercent = 10))
        assertEquals(DeleteBlock.UPLOADING, DeleteGuard.forGroup(groupDownloads(items, packages, "x", uploading).single()))
        val idle = listOf(item(1, DownloadStatus.COMPLETED), item(3, DownloadStatus.RUNNING))
        assertNull(DeleteGuard.forGroup(groupDownloads(idle, packages, "x").single()))
    }
}
