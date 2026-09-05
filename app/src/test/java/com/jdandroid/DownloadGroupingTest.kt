package com.jdandroid

import com.jdandroid.core.LiveProgress
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadPackage
import com.jdandroid.data.DownloadStatus
import com.jdandroid.ui.groupDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGroupingTest {

    /** Display name of the group without a package (translated from resources in the app). */
    private val LOOSE = "Ohne Paket"

    private fun item(id: Long, packageId: Long?, addedAt: Long = id) = DownloadItem(
        id = id, url = "https://example.test/$id", hosterId = "test",
        packageId = packageId, addedAt = addedAt
    )

    @Test
    fun eintraegeWerdenIhrenPaketenZugeordnet() {
        val packages = listOf(DownloadPackage(id = 1, name = "A"), DownloadPackage(id = 2, name = "B"))
        val groups = groupDownloads(listOf(item(10, 2), item(11, 1), item(12, 1)), packages, LOOSE)
        assertEquals(listOf("A", "B"), groups.map { it.pkg.name })
        assertEquals(listOf(11L, 12L), groups[0].items.map { it.id })
        assertEquals(listOf(10L), groups[1].items.map { it.id })
    }

    @Test
    fun leerePaketeErscheinenNicht() {
        val packages = listOf(DownloadPackage(id = 1, name = "A"), DownloadPackage(id = 2, name = "Leer"))
        val groups = groupDownloads(listOf(item(10, 1)), packages, LOOSE)
        assertEquals(listOf("A"), groups.map { it.pkg.name })
    }

    @Test
    fun eintraegeOhnePaketLandenUnterOhnePaket() {
        val groups = groupDownloads(listOf(item(10, null)), emptyList(), LOOSE)
        assertEquals(1, groups.size)
        assertEquals(LOOSE, groups[0].pkg.name)
        assertEquals(listOf(10L), groups[0].items.map { it.id })
    }

    @Test
    fun eintraegeMitGeloeschtemPaketBleibenSichtbar() {
        // Package 99 no longer exists; the entry must stay visible
        val packages = listOf(DownloadPackage(id = 1, name = "A"))
        val groups = groupDownloads(listOf(item(10, 1), item(11, 99), item(12, null)), packages, LOOSE)
        assertEquals(listOf("A", LOOSE), groups.map { it.pkg.name })
        assertEquals(setOf(11L, 12L), groups[1].items.map { it.id }.toSet())
        assertTrue(groups.flatMap { it.items }.map { it.id }.containsAll(listOf(10L, 11L, 12L)))
    }

    @Test
    fun entpackStandKommtAusDenLiveWertenAnDieGruppe() {
        val packages = listOf(DownloadPackage(id = 1, name = "A"))
        val extracting = item(10, 1).copy(status = DownloadStatus.EXTRACTING)
        val other = item(11, 1).copy(status = DownloadStatus.EXTRACTING)
        val done = item(12, 1).copy(status = DownloadStatus.COMPLETED)
        val live = mapOf(10L to LiveProgress(extractPercent = 42), 12L to LiveProgress(extractPercent = 99))
        val group = groupDownloads(listOf(extracting, other, done), packages, LOOSE, live).single()
        assertEquals(42, group.extractPercent(extracting))
        assertEquals(-1, group.extractPercent(other))
        // Package value: highest percent of the extracting entries
        assertEquals(42, group.extractPercent)
    }

    @Test
    fun ohneLiveWerteIstDerEntpackStandUnbekannt() {
        val extracting = item(10, 1).copy(status = DownloadStatus.EXTRACTING)
        val group = groupDownloads(listOf(extracting), listOf(DownloadPackage(id = 1, name = "A")), LOOSE).single()
        assertEquals(-1, group.extractPercent)
        assertEquals(-1, group.extractPercent(extracting))
    }

    @Test
    fun gleicherInhaltErgibtGleicheGruppen() {
        // Equal inputs must yield equal groups, otherwise the package header
        // recomposes on every progress tick
        val packages = listOf(DownloadPackage(id = 1, name = "A", addedAt = 5))
        val items = listOf(item(10, 1).copy(status = DownloadStatus.EXTRACTING), item(11, 1))
        val live = mapOf(10L to LiveProgress(extractPercent = 7))
        val first = groupDownloads(items, packages, LOOSE, live).single()
        val second = groupDownloads(items.map { it.copy() }, packages, LOOSE, live).single()
        assertEquals(first, second)
        assertNotEquals(first, groupDownloads(items, packages, LOOSE, mapOf(10L to LiveProgress(extractPercent = 8))).single())
    }

    @Test
    fun kennzahlenDesPaketsWerdenAusDenEintraegenBerechnet() {
        val packages = listOf(DownloadPackage(id = 1, name = "A"))
        val items = listOf(
            item(10, 1).copy(status = DownloadStatus.COMPLETED, fileSize = 100, downloadedBytes = 100),
            item(11, 1).copy(status = DownloadStatus.RUNNING, fileSize = 200, downloadedBytes = 50, speedBps = 7),
            // Unknown size: neither total nor progress count
            item(12, 1).copy(status = DownloadStatus.RUNNING, fileSize = -1, downloadedBytes = 999, speedBps = 3),
            item(13, 1).copy(status = DownloadStatus.FAILED),
            item(14, 1).copy(status = DownloadStatus.EXTRACTING)
        )
        val group = groupDownloads(items, packages, LOOSE, mapOf(14L to LiveProgress(extractPercent = 30))).single()
        assertEquals(300L, group.total)
        assertEquals(150L, group.done)
        assertEquals(10L, group.speed)
        assertEquals(1, group.finished)
        assertEquals(1, group.failed)
        assertTrue(group.active)
        assertTrue(group.extracting)
        assertEquals(30, group.extractPercent)

        val idle = groupDownloads(listOf(item(20, 1)), packages, LOOSE).single()
        assertFalse(idle.active)
        assertFalse(idle.extracting)
        assertEquals(-1, idle.extractPercent)
    }
}
