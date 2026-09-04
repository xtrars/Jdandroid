package com.jdandroid.engine

import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus

/**
 * Reine Entscheidungen ueber Multipart-Archive: welche Eintraege gehoeren zu
 * einem Set, und fehlen noch Teile? Ein Set ist immer auf ein Paket begrenzt -
 * gleichnamige Archive in zwei Paketen sind zwei Sets.
 */
internal object ArchiveSets {
    /** Noch nicht fertige Teile: blockieren das automatische Entpacken. */
    val ACTIVE = listOf(
        DownloadStatus.COLLECTED, DownloadStatus.QUEUED, DownloadStatus.RUNNING,
        DownloadStatus.PAUSED, DownloadStatus.EXTRACTING
    )

    /** Teile, die noch geladen werden (fuer das manuelle "Entpacken"). */
    val LOADING = listOf(
        DownloadStatus.COLLECTED, DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED
    )

    private fun baseOf(item: DownloadItem): String? =
        Extractor.archiveBase(Extractor.repairName(item.fileName ?: ""))

    /**
     * Fehlen noch Teile des Archivs [base] im Paket [selfPackageId]? Zaehlt auch
     * Eintraege desselben Pakets ohne Dateinamen (Sofortstart: der Name kommt
     * erst mit dem Aufloesen). FAILED-Teile und fremde Pakete zaehlen nicht.
     */
    fun pendingParts(
        all: List<DownloadItem>,
        selfId: Long,
        selfPackageId: Long?,
        base: String,
        statuses: List<DownloadStatus> = ACTIVE
    ): Boolean = all.any { other ->
        other.id != selfId && other.status in statuses && other.packageId == selfPackageId &&
            (baseOf(other) == base || (other.fileName == null && other.packageId != null))
    }

    /**
     * Alle fertigen oder entpackenden Eintraege des Sets von [id] (gleiches
     * Paket, gleiche Base), inklusive [id] selbst - auch wenn es gerade noch
     * RUNNING ist. Andere laufende Teile gehoeren nicht dazu: sie wuerden sonst
     * mitten im Download auf EXTRACTING gesetzt.
     */
    fun archiveSetIds(all: List<DownloadItem>, id: Long, base: String): List<Long> {
        val packageId = all.firstOrNull { it.id == id }?.packageId
        return (all.filter {
            it.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.EXTRACTING) &&
                it.packageId == packageId && baseOf(it) == base
        }.map { it.id } + id).distinct()
    }
}
