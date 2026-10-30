package com.jdandroid

import com.jdandroid.core.Clock
import com.jdandroid.engine.DownloadEngine
import com.jdandroid.engine.readChunk
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** The transfer loop gets full blocks from OkHttp's 8 KiB segments, but never waits longer than one sample. */
class ReadChunkTest {

    private class TestClock(var nanos: Long = 0) : Clock {
        override fun nowNanos() = nanos
    }

    private val size = DownloadEngine.TRANSFER_BUFFER

    @Test
    fun fuelltDenPufferAusKleinenSegmenten() {
        val total = 3 * size + 100
        val source = Buffer().write(ByteArray(total) { it.toByte() })
        // A plain read hands out a single segment, as from a response body
        val probe = Buffer().write(ByteArray(64 * 1024))
        assertTrue(probe.inputStream().read(ByteArray(64 * 1024)) <= 8192)

        val input = source.inputStream()
        val buffer = ByteArray(size)
        val clock = TestClock()
        val chunks = mutableListOf<Int>()
        var copied = 0
        while (true) {
            val read = input.readChunk(buffer, clock, 500)
            if (read < 0) break
            for (i in 0 until read) assertEquals((copied + i).toByte(), buffer[i])
            copied += read
            chunks += read
        }
        assertEquals(listOf(size, size, size, 100), chunks)
        assertEquals(total, copied)
    }

    @Test
    fun langsameLeitungLiefertNachSpaetestensEinemIntervall() {
        val clock = TestClock()
        // 1000 bytes per read, 100 ms per read
        val slow = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                clock.nanos += 100_000_000
                return minOf(1000, len)
            }
        }
        assertEquals(5000, slow.readChunk(ByteArray(size), clock, 500))
    }

    @Test
    fun leererStromMeldetEnde() {
        assertEquals(-1, Buffer().inputStream().readChunk(ByteArray(size), TestClock(), 500))
    }
}
