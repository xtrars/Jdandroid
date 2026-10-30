package com.jdandroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jdandroid.engine.Extractor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RAR extraction through the native 7-Zip library. Native code calls the
 * app's callbacks (next volume, output stream) by name, so this is what a
 * code shrinker must not break. Fixtures come from tools/mkrar.py.
 */
@RunWith(AndroidJUnit4::class)
class RarExtractionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(instrumentation.targetContext.cacheDir, "rar-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun fixture(name: String): File = File(dir, name).also { file ->
        instrumentation.context.assets.open("rar/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun pattern(size: Int) = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }

    @Test
    fun einteiligesArchivMitOrdner() {
        val out = File(dir, "out")
        assertNull(Extractor.extract(fixture("single.rar"), out, emptyList()))
        assertEquals("JDAndroid RAR test\n", File(out, "docs/readme.txt").readText())
        assertArrayEquals(pattern(5000), File(out, "data.bin").readBytes())
    }

    @Test
    fun einteiligesArchivFlach() {
        val out = File(dir, "out")
        Extractor.extract(fixture("single.rar"), out, emptyList(), flat = true)
        assertEquals("JDAndroid RAR test\n", File(out, "readme.txt").readText())
    }

    @Test
    fun mehrteiligesArchivUeberAlleTeile() {
        val first = fixture("set.part1.rar")
        fixture("set.part2.rar")
        fixture("set.part3.rar")
        val out = File(dir, "out")
        val progress = mutableListOf<Long>()
        assertNull(Extractor.extract(first, out, emptyList(), progress = { done, _ -> progress += done }))
        assertEquals("JDAndroid RAR test\n", File(out, "info.txt").readText())
        // big.bin spans all three volumes
        assertArrayEquals(pattern(96 * 1024), File(out, "big.bin").readBytes())
        assertEquals(96L * 1024 + 19, progress.last())
    }
}
