package com.jdandroid

import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.LinkSink
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure parts of [LinkSink]: the source shown on a package and the unique
 * index on the URL that catches duplicates the pre-check misses (two
 * Click'n'Load requests inserting at the same time). Queuing itself needs
 * the app and runs only on the device.
 */
class LinkSinkTest : SchemaDbTest() {

    @Test
    fun quelleZeigtNurDenHostOhneWww() {
        assertEquals("example.org", LinkSink.displaySource("https://www.example.org/thread/42?x=1"))
        assertEquals("forum.example.org", LinkSink.displaySource(" http://forum.example.org:8080/x "))
        // Scheme case-insensitive, host spelling kept
        assertEquals("Example.ORG", LinkSink.displaySource("HTTPS://Example.ORG/x"))
    }

    @Test
    fun quelleOhneUrlBleibtTextUndWirdGekuerzt() {
        assertEquals("Mein Forum", LinkSink.displaySource("  Mein Forum  "))
        assertEquals("ftp://example.org/x", LinkSink.displaySource("ftp://example.org/x"))
        assertEquals(80, LinkSink.displaySource("a".repeat(200)).length)
        assertEquals("", LinkSink.displaySource("   "))
    }

    @Test
    fun zweiterEintragMitGleicherUrlWirdVomIndexAbgewiesen() {
        item(1, "a.rar", DownloadStatus.COLLECTED)
        // INSERT OR IGNORE like Room's OnConflictStrategy.IGNORE: no row, no error
        val inserted = execute(
            "INSERT OR IGNORE INTO downloads (url, hosterId, fileSize, downloadedBytes, speedBps, status, " +
                "attempts, retryAt, online, addedAt) VALUES (:url, 'h', -1, 0, 0, 'COLLECTED', 0, 0, 0, 2)",
            "url" to "https://h/1"
        )
        assertEquals(0, inserted)
        assertEquals(1, count("SELECT COUNT(*) FROM downloads WHERE url = :url", "url" to "https://h/1"))
        // A different URL is still accepted
        assertEquals(1, execute(
            "INSERT OR IGNORE INTO downloads (url, hosterId, fileSize, downloadedBytes, speedBps, status, " +
                "attempts, retryAt, online, addedAt) VALUES (:url, 'h', -1, 0, 0, 'COLLECTED', 0, 0, 0, 2)",
            "url" to "https://h/2"
        ))
    }
}
