package com.jdandroid

import com.jdandroid.engine.hashFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** File checksum and cancellation of the check on pause. */
class HashFileTest {

    private fun tempFile(content: ByteArray): File =
        File.createTempFile("hash", ".bin").apply { deleteOnExit(); writeBytes(content) }

    @Test
    fun `md5 und sha256 stimmen`() = runBlocking {
        val file = tempFile("abc".toByteArray())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hashFile(file, "MD5"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashFile(file, "SHA-256"))
    }

    @Test
    fun `mehrere Bloecke werden zusammenhaengend gehasht`() = runBlocking {
        val file = tempFile(ByteArray(200 * 1024) { it.toByte() })
        val expected = java.security.MessageDigest.getInstance("MD5").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, hashFile(file, "MD5"))
    }

    @Test
    fun `Abbruch waehrend der Pruefung beendet sie`() = runBlocking {
        val file = tempFile(ByteArray(200 * 1024))
        var finished = false
        // UNDISPATCHED: runs until the first block switch, then cancelled
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            hashFile(file, "MD5")
            finished = true
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(!finished)
    }
}
