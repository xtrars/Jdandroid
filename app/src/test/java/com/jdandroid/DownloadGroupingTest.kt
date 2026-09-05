package com.jdandroid

import com.jdandroid.core.LiveProgress
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadPackage
import com.jdandroid.data.DownloadStatus
import com.jdandroid.ui.groupDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGroupingTest {

    /** Anzeigename der Gruppe ohne Paket (in der App uebersetzt aus den Ressourcen). */
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
        // Paket 99 existiert nicht mehr - der Eintrag darf nicht verschwinden
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
        // Paketwert: hoechster Stand der gerade entpackenden Eintraege
        assertEquals(42, group.extractPercent)
    }

    @Test
    fun ohneLiveWerteIstDerEntpackStandUnbekannt() {
        val extracting = item(10, 1).copy(status = DownloadStatus.EXTRACTING)
        val group = groupDownloads(listOf(extracting), listOf(DownloadPackage(id = 1, name = "A")), LOOSE).single()
        assertEquals(-1, group.extractPercent)
        assertEquals(-1, group.extractPercent(extracting))
    }
}
