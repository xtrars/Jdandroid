package com.jdandroid

import com.jdandroid.core.LiveProgress
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.engine.SessionProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** The notification bar only moves forward: finished entries keep their share, unknown sizes do not count. */
class SessionProgressTest {

    private fun item(id: Long, status: DownloadStatus, size: Long, bytes: Long = 0) = DownloadItem(
        id = id, url = "https://example.org/$id", hosterId = "rapidgator", packageId = 1,
        fileName = "$id.bin", fileSize = size, downloadedBytes = bytes, status = status
    )

    @Test
    fun fertigerEintragZaehltVollUndFaelltNichtHeraus() {
        val running = listOf(item(1, DownloadStatus.RUNNING, 100, 100), item(2, DownloadStatus.RUNNING, 100, 0))
        assertEquals(100L to 200L, SessionProgress.ratio(running, emptyMap()))
        val afterFirst = listOf(item(1, DownloadStatus.COMPLETED, 100, 100), item(2, DownloadStatus.RUNNING, 100, 10))
        assertEquals(110L to 200L, SessionProgress.ratio(afterFirst, emptyMap()))
        // The finished entry counts in full even with an older byte snapshot; extracting is finished too
        val snapshot = listOf(item(1, DownloadStatus.EXTRACTING, 100, 70), item(2, DownloadStatus.QUEUED, 100))
        assertEquals(100L to 200L, SessionProgress.ratio(snapshot, emptyMap()))
    }

    @Test
    fun liveWerteErsetzenDieDatenbankUndBleibenUnterDerGroesse() {
        val items = listOf(item(1, DownloadStatus.RUNNING, 100, 20), item(2, DownloadStatus.RUNNING, 100, 20))
        val live = mapOf(1L to LiveProgress(downloadedBytes = 60), 2L to LiveProgress(downloadedBytes = 250))
        assertEquals(160L to 200L, SessionProgress.ratio(items, live))
        // -1 in the live entry (extraction only) keeps the database bytes
        assertEquals(40L to 200L, SessionProgress.ratio(items, mapOf(1L to LiveProgress(extractPercent = 5))))
    }

    @Test
    fun unbekannteGroesseZaehltNicht() {
        val items = listOf(item(1, DownloadStatus.RUNNING, 0, 500), item(2, DownloadStatus.RUNNING, 100, 50))
        assertEquals(50L to 100L, SessionProgress.ratio(items, emptyMap()))
        assertEquals(-1, SessionProgress.percent(0, 0))
        assertEquals(50, SessionProgress.percent(50, 100))
        assertEquals(100, SessionProgress.percent(150, 100))
    }

    @Test
    fun sitzungSammeltUndLeert() {
        val session = SessionProgress()
        session.add(listOf(1, 2))
        session.add(listOf(2, 3))
        assertEquals(listOf(1L, 2L, 3L), session.ids())
        session.clear()
        assertEquals(emptyList<Long>(), session.ids())
    }
}
