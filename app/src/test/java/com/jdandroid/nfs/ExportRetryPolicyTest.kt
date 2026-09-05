package com.jdandroid.nfs

import com.jdandroid.core.Clock
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.DownloadStatus
import com.jdandroid.engine.ExportRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRetryPolicyTest {

    private class TestClock(var nanos: Long = 0) : Clock {
        override fun nowNanos() = nanos
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000 }
    }

    private fun item(id: Long, status: DownloadStatus, note: String?, path: String?) =
        DownloadItem(id = id, url = "https://h/$id", hosterId = "h", fileName = "f$id",
            status = status, errorMessage = note, localPath = path)

    @Test
    fun `hoechstens alle 60 Sekunden, Netzwechsel sofort`() {
        val clock = TestClock()
        var last: Long? = null
        assertTrue(ExportRetryPolicy.isDue(clock.nowMillis(), last, forced = false))
        last = clock.nowMillis()
        clock.advanceMs(59_999)
        assertFalse(ExportRetryPolicy.isDue(clock.nowMillis(), last, forced = false))
        assertTrue(ExportRetryPolicy.isDue(clock.nowMillis(), last, forced = true))
        clock.advanceMs(1)
        assertTrue(ExportRetryPolicy.isDue(clock.nowMillis(), last, forced = false))
        assertEquals(60_000L, ExportRetryPolicy.MIN_INTERVAL_MS)
    }

    @Test
    fun `nur fertige Eintraege mit Vermerk und Pfad, je Pfad einmal`() {
        val pending = DownloadNotes.EXPORT_PENDING
        val groups = ExportRetryPolicy.groups(
            listOf(
                item(1, DownloadStatus.COMPLETED, pending, "/dl/a.mkv"),
                item(2, DownloadStatus.COMPLETED, pending, "/dl/Paket"),
                item(3, DownloadStatus.COMPLETED, pending, "/dl/Paket"),
                item(4, DownloadStatus.COMPLETED, DownloadNotes.WAITING_PARTS, "/dl/b.rar"),
                item(5, DownloadStatus.COMPLETED, null, "/dl/c.mkv"),
                item(6, DownloadStatus.RUNNING, pending, "/dl/d.mkv"),
                item(7, DownloadStatus.COMPLETED, pending, null),
                item(8, DownloadStatus.COMPLETED, pending, "")
            )
        )
        assertEquals(setOf("/dl/a.mkv", "/dl/Paket"), groups.keys)
        assertEquals(listOf(1L), groups["/dl/a.mkv"]!!.map { it.id })
        assertEquals(listOf(2L, 3L), groups["/dl/Paket"]!!.map { it.id })
    }

    @Test
    fun `Vermerk-Code bleibt stabil`() {
        assertEquals("EXPORT_PENDING", DownloadNotes.EXPORT_PENDING)
    }
}
