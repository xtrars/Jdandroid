package com.jdandroid

import com.jdandroid.core.LiveProgress
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.ui.overlayProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Overlaying ProgressBus live values on database entries. */
class ProgressOverlayTest {

    private fun item(id: Long, status: DownloadStatus = DownloadStatus.RUNNING) = DownloadItem(
        id = id, url = "https://example.test/$id", hosterId = "test", status = status,
        fileSize = 1_000, downloadedBytes = 100, speedBps = 0
    )

    @Test
    fun eintragMitLiveWertenBekommtBytesUndGeschwindigkeit() {
        val result = overlayProgress(listOf(item(1)), mapOf(1L to LiveProgress(750, 5_000)))
        assertEquals(750L, result[0].downloadedBytes)
        assertEquals(5_000L, result[0].speedBps)
        // Missing live values keep the database value
        assertEquals(1_000L, result[0].fileSize)
        assertEquals(DownloadStatus.RUNNING, result[0].status)
    }

    @Test
    fun eintragOhneLiveWerteBleibtDieselbeInstanz() {
        val original = item(2)
        val result = overlayProgress(listOf(item(1), original), mapOf(1L to LiveProgress(750, 5_000)))
        assertSame(original, result[1])
    }

    @Test
    fun ohneLiveWerteBleibtDieListeUnveraendert() {
        val items = listOf(item(1), item(2))
        assertSame(items, overlayProgress(items, emptyMap()))
    }

    @Test
    fun entpackProzentAendertBytestandUndGeschwindigkeitNicht() {
        val extracting = item(3, DownloadStatus.EXTRACTING).copy(downloadedBytes = 1_000)
        val result = overlayProgress(listOf(extracting), mapOf(3L to LiveProgress(extractPercent = 42)))
        // -1 = no live value: database value stays
        assertEquals(1_000L, result[0].downloadedBytes)
        assertEquals(0L, result[0].speedBps)
    }
}
