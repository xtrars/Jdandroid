package com.jdandroid

import com.jdandroid.engine.feed
import com.jdandroid.engine.hashAlgorithmFor
import com.jdandroid.engine.hex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Streaming file checksum and cancellation of the prefix read on pause. */
class HashFileTest {

    private fun tempFile(content: ByteArray): File =
        File.createTempFile("hash", ".bin").apply { deleteOnExit(); writeBytes(content) }

    private suspend fun hashFile(file: File, algorithm: String): String =
        MessageDigest.getInstance(algorithm).apply { feed(file) }.hex()

    @Test
    fun `md5 und sha256 stimmen`() = runBlocking {
        val file = tempFile("abc".toByteArray())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hashFile(file, "MD5"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashFile(file, "SHA-256"))
    }

    @Test
    fun `mehrere Bloecke werden zusammenhaengend gehasht`() = runBlocking {
        val file = tempFile(ByteArray(200 * 1024) { it.toByte() })
        val expected = MessageDigest.getInstance("MD5").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, hashFile(file, "MD5"))
    }

    @Test
    fun `Fortsetzung hasht das Praefix aus der Datei und den Rest aus dem Puffer`() = runBlocking {
        val all = ByteArray(300 * 1024) { (it * 7).toByte() }
        val prefix = tempFile(all.copyOfRange(0, 100 * 1024))
        val expected = MessageDigest.getInstance("MD5").digest(all).joinToString("") { "%02x".format(it) }

        val digest = MessageDigest.getInstance("MD5")
        digest.feed(prefix)
        val buffer = ByteArray(64 * 1024)
        all.inputStream().apply { skip(prefix.length()) }.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        assertEquals(expected, digest.hex())
    }

    @Test
    fun `Algorithmus folgt der Laenge des Hashes`() {
        assertEquals("MD5", hashAlgorithmFor("900150983cd24fb0d6963f7d28e17f72"))
        assertEquals("SHA-1", hashAlgorithmFor("a9993e364706816aba3e25717850c26c9cd0d89d"))
        assertEquals("SHA-256", hashAlgorithmFor("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertNull(hashAlgorithmFor("abc"))
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
