package com.jdandroid

import com.jdandroid.core.LiveProgress
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.ui.overlayProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Reine Funktion: Live-Werte des ProgressBus ueber Datenbank-Eintraege legen. */
class ProgressOverlayTest {

    private fun item(id: Long, status: DownloadStatus = DownloadStatus.RUNNING) = DownloadItem(
        id = id, url = "https://example.test/$id", hosterId = "test", status = status,
        fileSize = 1_000, downloadedBytes = 100, speedBps = 0, extractProgress = -1
    )

    @Test
    fun eintragMitLiveWertenBekommtBytesUndGeschwindigkeit() {
        val result = overlayProgress(listOf(item(1)), mapOf(1L to LiveProgress(750, 5_000)))
        assertEquals(750L, result[0].downloadedBytes)
        assertEquals(5_000L, result[0].speedBps)
        // Nicht gelieferte Werte bleiben aus der Datenbank
        assertEquals(-1, result[0].extractProgress)
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
    fun entpackProzentUeberlagertNurDenEntpackStand() {
        val extracting = item(3, DownloadStatus.EXTRACTING).copy(downloadedBytes = 1_000)
        val result = overlayProgress(listOf(extracting), mapOf(3L to LiveProgress(extractPercent = 42)))
        assertEquals(42, result[0].extractProgress)
        // Bytestand (-1 = kein Live-Wert) bleibt der Datenbankwert
        assertEquals(1_000L, result[0].downloadedBytes)
        assertEquals(0L, result[0].speedBps)
    }
}
