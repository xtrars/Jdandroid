package com.jdandroid

import com.jdandroid.core.FileNames
import com.jdandroid.engine.ArchiveCoordinator
import com.jdandroid.engine.ExtractionRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Archive sets are scoped per package: folders, registry keys and the process-wide registry itself. */
class ArchiveCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = listOf("1/film", "2/film", "0/film", "1/other")

    // Process-wide registry: release every key and id a test may have left behind
    @After
    fun freeRegistry() = keys.forEach { ExtractionRegistry.finish(it, (1L..9L).toList()) }

    @Test
    fun archivordnerJePaketUnterDemDownloadordner() {
        val root = tmp.newFolder("downloads")
        val a = ArchiveCoordinator.archiveDir(root, 1)
        val b = ArchiveCoordinator.archiveDir(root, 2)
        assertTrue(a.isDirectory)
        assertTrue(b.isDirectory)
        assertNotEquals(a, b)
        assertEquals(root, a.parentFile?.parentFile)
        // Entries without a package share folder 0
        assertEquals(ArchiveCoordinator.archiveDir(root, null), ArchiveCoordinator.archiveDir(root, null))
        assertNotEquals(a, ArchiveCoordinator.archiveDir(root, null))
        // Volumes of the same name in two packages are two files
        File(a, "film.part1.rar").writeText("A")
        File(b, "film.part1.rar").writeText("B")
        assertEquals("A", File(a, "film.part1.rar").readText())
        assertEquals("B", File(b, "film.part1.rar").readText())
    }

    @Test
    fun paketordnerKannNieMitDemArchivordnerKollidieren() {
        val root = tmp.newFolder("dl")
        val archives = ArchiveCoordinator.archiveDir(root, 1).parentFile!!
        for (name in listOf(archives.name, ".${archives.name}", "  ${archives.name}")) {
            assertNotEquals(archives.name, FileNames.clean(name))
        }
    }

    @Test
    fun schluesselTrenntPaketeUndOrdnetPaketloseDerNull() {
        assertNotEquals(ArchiveCoordinator.setKey(1, "film"), ArchiveCoordinator.setKey(2, "film"))
        assertEquals(ArchiveCoordinator.setKey(null, "film"), ArchiveCoordinator.setKey(0, "film"))
        assertNotEquals(ArchiveCoordinator.setKey(1, "film"), ArchiveCoordinator.setKey(1, "other"))
    }

    @Test
    fun gleicherArchivnameInAnderemPaketIstKeinKonflikt() {
        assertTrue(ExtractionRegistry.start(ArchiveCoordinator.setKey(1, "film"), listOf(1, 2)))
        assertTrue(ExtractionRegistry.start(ArchiveCoordinator.setKey(2, "film"), listOf(3)))
        assertTrue(ExtractionRegistry.start(ArchiveCoordinator.setKey(null, "film"), listOf(4)))
        assertEquals(setOf(1L, 2L, 3L, 4L), ExtractionRegistry.activeIds().toSet())
    }

    @Test
    fun doppelterStartWirdAbgelehntUndFinishGibtFrei() {
        assertTrue(ExtractionRegistry.start("1/film", listOf(1, 2)))
        assertTrue(ExtractionRegistry.isActive("1/film"))
        assertFalse(ExtractionRegistry.start("1/film", listOf(1, 2)))
        assertFalse(ExtractionRegistry.start("1/film", listOf(9)))
        // The rejected start must not register foreign ids
        assertEquals(setOf(1L, 2L), ExtractionRegistry.activeIds().toSet())
        ExtractionRegistry.finish("1/film", listOf(1, 2))
        assertFalse(ExtractionRegistry.isActive("1/film"))
        assertTrue(ExtractionRegistry.activeIds().isEmpty())
        assertTrue(ExtractionRegistry.start("1/film", listOf(1, 2)))
    }

    @Test
    fun finishEntferntNurDieEigenenKennungen() {
        ExtractionRegistry.start("1/film", listOf(1, 2))
        ExtractionRegistry.start("1/other", listOf(3))
        ExtractionRegistry.finish("1/film", listOf(1, 2))
        assertTrue(ExtractionRegistry.isActive("1/other"))
        assertEquals(listOf(3L), ExtractionRegistry.activeIds())
        // Finishing a set that never started changes nothing
        ExtractionRegistry.finish("2/film", listOf(8))
        assertTrue(ExtractionRegistry.isActive("1/other"))
        assertEquals(listOf(3L), ExtractionRegistry.activeIds())
    }
}
