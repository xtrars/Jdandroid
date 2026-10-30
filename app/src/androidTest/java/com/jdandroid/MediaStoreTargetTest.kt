package com.jdandroid

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.jdandroid.engine.MediaStoreTarget
import com.jdandroid.engine.Placed
import com.jdandroid.engine.StorageTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The public Downloads/JDAndroid folder through MediaStore: storing, the name
 * MediaStore picks on a collision, fetching back and removing by that name.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class MediaStoreTargetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "jdtest-${System.nanoTime()}"
    private lateinit var dir: File
    private lateinit var target: MediaStoreTarget

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "mediastore-test").apply { deleteRecursively(); mkdirs() }
        target = MediaStoreTarget(context)
    }

    @After
    fun tearDown() {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%")
        )
        dir.deleteRecursively()
    }

    private fun temp(content: String): File = File(dir, "$prefix.part").apply { writeText(content) }

    @Test
    fun ablegenZurueckholenUndLoeschen() = runBlocking {
        val name = "$prefix.bin"
        val temp = temp("eins")
        val placed = target.finish(temp, name, null)
        assertEquals(Placed("${StorageTarget.MEDIA_STORE_DISPLAY}$name"), placed)
        assertFalse(temp.exists())

        val back = File(dir, "back")
        assertTrue(target.restore(name, back))
        assertEquals("eins", back.readText())

        assertTrue(target.delete(placed!!.path))
        assertFalse(target.restore(name, File(dir, "again")))
        assertFalse(target.delete(placed.path))
    }

    @Test
    fun gleicherNameBekommtDenVonMediaStoreVergebenenNamen() = runBlocking {
        val name = "$prefix.bin"
        val first = target.finish(temp("eins"), name, null)!!
        val second = target.finish(temp("zwei"), name, null)!!
        val storedSecond = StorageTarget.storedName(second.path)
        assertNotEquals(first.path, second.path)
        assertTrue(storedSecond.startsWith(prefix))

        val back = File(dir, "back")
        assertTrue(target.restore(storedSecond, back))
        assertEquals("zwei", back.readText())
        assertTrue(target.restore(name, back))
        assertEquals("eins", back.readText())
    }

    @Test
    fun ordnerexportLandetImUnterordnerUndRaeumtLokalAuf() = runBlocking {
        val extracted = File(dir, "Paket").apply { mkdirs() }
        File(extracted, "$prefix-a.txt").writeText("a")
        File(extracted, "sub").mkdirs()
        File(extracted, "sub/$prefix-b.txt").writeText("b")

        val placed = target.exportDirectory(extracted, "$prefix-Paket", null, false)

        assertEquals(Placed("${StorageTarget.MEDIA_STORE_DISPLAY}$prefix-Paket"), placed)
        assertNull(placed.error)
        assertFalse(extracted.exists())
        // Only files directly in Downloads/JDAndroid are fetched back, never same-named ones in package folders
        assertFalse(target.restore("$prefix-a.txt", File(dir, "back")))
    }
}
