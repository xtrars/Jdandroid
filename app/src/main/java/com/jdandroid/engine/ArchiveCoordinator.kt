package com.jdandroid.engine

import com.jdandroid.JdApp
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.Clock
import com.jdandroid.core.FileNames
import com.jdandroid.core.LiveProgress
import com.jdandroid.core.ProgressBus
import com.jdandroid.data.AccountRefresher
import com.jdandroid.data.DownloadDao
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.SettingsRepository
import com.jdandroid.data.renameFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Abschluss fertiger Downloads: einfache Dateien wandern ins Ziel, Archive
 * warten auf ihre Teile und werden (wenn aktiviert) entpackt und exportiert.
 * Ein Archiv-Set (alle Teile eines Mehrteilers im Paket) wird immer als
 * Ganzes auf EXTRACTING gesetzt und als Ganzes abgeschlossen; welches Archiv
 * gerade laeuft, weiss prozessweit die [ExtractionRegistry].
 */
internal class ArchiveCoordinator(
    private val app: JdApp,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val storage: StorageTarget,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val onStateChanged: () -> Unit,
    /** Nach jedem Entpackvorgang: die Engine darf wieder pumpen. */
    private val onExtractionFinished: () -> Unit
) {
    /**
     * Serialisiert den Download-Abschluss: verhindert, dass zwei gleichzeitig
     * fertige Teile desselben Archivs sich gegenseitig als "noch ausstehend"
     * sehen und das Entpacken dadurch ganz ausbleibt. Die Engine haelt sie
     * auch beim Pausieren/Zurueckstufen, damit ein Abschluss zu Ende kommt.
     */
    val completionMutex = Mutex()

    /** Entpacken/Export laufen in eigenen Jobs, immer nur einer gleichzeitig. */
    private val extractLimiter = Semaphore(1)
    /** Prozessweit (siehe [ExtractionRegistry]): ueberlebt einen Neustart des Dienstes. */
    private val extracting get() = ExtractionRegistry.count

    /** Laufende Entpackvorgaenge (alle Dienst-Instanzen). */
    val activeCount: Int get() = extracting.get()

    /** Ein Archiv-Set: ausloesender Eintrag, sein Paket und der Archivschluessel. */
    private data class ArchiveSet(val id: Long, val packageId: Long?, val base: String)

    /**
     * Abschluss eines Downloads: Archive werden (wenn aktiviert) automatisch
     * entpackt, sobald alle Teile vorliegen; alles andere wird direkt exportiert.
     *
     * Nicht abbrechbar: eine Pause waehrend des Exports liess vorher die Kopie
     * zu Ende laufen, den Statuswechsel aber scheitern - Eintrag "pausiert" bei
     * 100 %, Teildatei weg, beim Fortsetzen Neudownload plus Duplikat.
     */
    suspend fun completeDownload(id: Long, temp: File, originalName: String) = withContext(NonCancellable) {
        var fileName = originalName
        var base = ArchiveNames.archiveBase(fileName)
        if (base == null) {
            // "name part1 rar" (Hoster hat Punkte durch Leerzeichen ersetzt)
            val repaired = ArchiveNames.repairName(fileName)
            if (ArchiveNames.archiveBase(repaired) != null) {
                fileName = repaired
                dao.renameFile(id, fileName)
                base = ArchiveNames.archiveBase(fileName)
            }
        }
        if (base == null) {
            // Name ohne Archiv-Endung, Inhalt aber ein Archiv (falscher oder
            // fehlender Name vom Hoster): Endung anhand der Magic Bytes ergaenzen
            Extractor.sniffExtension(temp)?.let { ext ->
                fileName = "$fileName.$ext"
                dao.renameFile(id, fileName)
                base = ArchiveNames.archiveBase(fileName)
            }
        }
        val autoExtract = settings.currentAutoExtract()

        if (!autoExtract || base == null) {
            // Verschieben und Statuswechsel unter der Abschluss-Sperre: pause()/
            // Netzwechsel warten so, statt den Eintrag mit bereits verschobener
            // Teildatei auf PAUSED/QUEUED zu setzen (Neudownload plus Duplikat)
            val packageId = completionMutex.withLock {
                val path = storage.finish(temp, fileName)
                markCompleted(id, path, null)
                dao.byId(id)?.packageId
            }
            // Ein wartendes Archiv-Set desselben Pakets kann jetzt vollstaendig sein
            retryWaitingSets(packageId)
            return@withContext
        }

        val archiveFile = File(storage.downloadDir(), fileName)
        // Entscheidung unter der Sperre: zwei gleichzeitig fertige Teile duerfen
        // sich nicht gegenseitig als "noch ausstehend" sehen.
        val shouldExtract = completionMutex.withLock {
            val packageId = dao.byId(id)?.packageId
            // Gleichnamiges Archiv eines anderen Pakets liegt bereits flach im
            // App-Ordner: nicht ueberschreiben, sondern als normale Datei ablegen
            val clash = archiveFile.isFile && temp.path != archiveFile.path &&
                dao.countSameNameElsewhere(fileName, packageId) > 0
            if (clash) {
                markCompleted(id, storage.finish(temp, fileName), "Gleichnamiges Archiv eines anderen Pakets vorhanden, nicht entpackt")
                return@withLock null
            }
            // Archiv-Volume unter echtem Namen im App-Ordner ablegen, damit
            // Multipart-Teile zueinander finden
            if (temp.path != archiveFile.path) {
                archiveFile.delete()
                temp.renameTo(archiveFile)
            }
            val pending = dao.pendingActiveParts(packageId, base!!, id) > 0
            if (pending || ExtractionRegistry.isActive(base!!)) {
                markCompleted(id, archiveFile.absolutePath, WAITING_NOTE)
                null
            } else {
                // Alle Teile des Sets zeigen "wird entpackt", nicht nur der zuletzt
                // fertige - sonst wirkt der Zustand willkuerlich verteilt
                dao.setExtractingSet(dao.archiveSetIds(packageId, base!!, id))
                ArchiveSet(id, packageId, base!!)
            }
        }
        if (shouldExtract == null) return@withContext

        startExtraction(shouldExtract, archiveFile)
    }

    /**
     * Set ist vollstaendig und bereits EXTRACTING: erstes Volume suchen und
     * entpacken. Fehlt es, alle Teile des Sets zurueck auf fertig - nicht nur
     * den ausloesenden, sonst bleiben die uebrigen dauerhaft EXTRACTING.
     */
    private suspend fun startExtraction(set: ArchiveSet, archiveFile: File) {
        val primary = Extractor.findPrimaryVolume(storage.downloadDir(), set.base)
        if (primary == null) {
            dao.byId(set.id)?.let { AccountRefresher.refreshHoster(app, it.hosterId) }
            dao.completeExtractingSet(archiveSetIds(set), archiveFile.absolutePath, "Erstes Archiv-Teil fehlt, nicht entpackt")
            return
        }
        // Entpacken in eigenem Job: der Download-Slot wird sofort frei, die
        // Warteschlange steht nicht minutenlang hinter einem grossen RAR.
        launchExtraction(set, primary, archiveFile)
    }

    /**
     * Fertige Archiv-Teile mit [WAITING_NOTE] im Paket erneut pruefen: sobald der
     * letzte ausstehende Eintrag des Pakets einen Namen bekommt oder als
     * Nicht-Archiv fertig wird, stoesst das sonst niemand mehr an.
     */
    suspend fun retryWaitingSets(packageId: Long?) = withContext(NonCancellable) {
        if (packageId == null) return@withContext
        val ready = completionMutex.withLock {
            dao.waitingParts(packageId, WAITING_NOTE).groupBy { it.archiveKey!! }
                .mapNotNull { (base, parts) ->
                    val self = parts.first()
                    if (dao.pendingActiveParts(packageId, base, self.id) > 0 || ExtractionRegistry.isActive(base)) {
                        return@mapNotNull null
                    }
                    dao.setExtractingSet(dao.archiveSetIds(packageId, base, self.id))
                    ArchiveSet(self.id, packageId, base) to File(storage.downloadDir(), self.fileName!!)
                }
        }
        ready.forEach { (set, archiveFile) -> startExtraction(set, archiveFile) }
    }

    /** Siehe [com.jdandroid.data.ArchiveSets.SET_IDS]: fertige/entpackende Teile des Sets, inklusive Ausloeser. */
    private suspend fun archiveSetIds(set: ArchiveSet): List<Long> =
        dao.archiveSetIds(set.packageId, set.base, set.id)

    private suspend fun launchExtraction(set: ArchiveSet, primary: File, archiveFile: File) {
        val setIds = archiveSetIds(set)
        // Laeuft dieses Archiv bereits (z.B. aus einer frueheren Dienst-Instanz),
        // nicht ein zweites Mal entpacken; die laufende Instanz schliesst das Set ab
        if (!ExtractionRegistry.start(set.base, setIds)) return
        extracting.incrementAndGet()
        onStateChanged()
        scope.launch {
            try {
                extractAndExport(set, setIds, primary, archiveFile)
            } finally {
                ExtractionRegistry.finish(set.base, setIds)
                extracting.decrementAndGet()
                onStateChanged()
                onExtractionFinished()
            }
        }
    }

    /**
     * Nachtraegliches Entpacken eines fertigen Downloads (Aktionsmenue). Alle
     * Teile des Archiv-Sets werden bei Bedarf aus dem Zielordner (SAF oder
     * Downloads/JDAndroid) in den App-Ordner zurueckgeholt. Liefert eine
     * Fehlermeldung oder null, wenn das Entpacken gestartet wurde.
     */
    suspend fun extractNow(id: Long): String? {
        // Als laufender Vorgang zaehlen, bevor Dateien zurueckgeholt werden:
        // sonst haelt sich der frisch gestartete Dienst fuer untaetig, beendet
        // sich, und die naechste Instanz reiht die EXTRACTING-Eintraege neu ein
        extracting.incrementAndGet()
        onStateChanged()
        try {
            return extractNowInner(id)
        } finally {
            extracting.decrementAndGet()
            onStateChanged()
        }
    }

    private suspend fun extractNowInner(id: Long): String? {
        val item = dao.byId(id) ?: return "Eintrag nicht gefunden"
        if (item.status == DownloadStatus.EXTRACTING) return "Wird bereits entpackt"
        if (item.status != DownloadStatus.COMPLETED) return "Nur fertige Downloads lassen sich entpacken"
        var name = item.fileName ?: return "Dateiname unbekannt"
        var base = ArchiveNames.archiveBase(name)
        val downloadDir = storage.downloadDir()
        if (base == null) {
            // Namen wie "name part1 rar": alle Teile des Sets umbenennen (Datei
            // im App-Ordner bzw. aus dem Zielordner zurueckgeholt) und in der
            // Datenbank korrigieren. archiveKey ist bereits aus dem reparierten
            // Namen berechnet, findet also auch diese Teile.
            val repaired = ArchiveNames.repairName(name)
            val repairedBase = ArchiveNames.archiveBase(repaired)
            if (repairedBase != null) {
                for (part in dao.completedParts(item.packageId, repairedBase)) {
                    val oldName = part.fileName ?: continue
                    val newName = ArchiveNames.repairName(oldName)
                    val local = File(downloadDir, newName)
                    if (!local.isFile) {
                        val oldLocal = File(downloadDir, oldName)
                        if (oldLocal.isFile) oldLocal.renameTo(local) else restoreArchive(part, local)
                    }
                    if (newName != oldName) dao.renameFile(part.id, newName)
                }
                name = repaired
                base = repairedBase
            }
        }
        if (base == null) {
            // Vielleicht ein Archiv ohne passende Endung
            val local = File(downloadDir, name).takeIf { it.isFile }
                ?: run { val f = File(downloadDir, name); if (restoreArchive(item, f)) f else null }
            val ext = local?.let { Extractor.sniffExtension(it) } ?: return "Kein Archiv: $name"
            val renamed = File(downloadDir, "$name.$ext")
            local.renameTo(renamed)
            name = renamed.name
            dao.renameFile(id, name)
            base = ArchiveNames.archiveBase(name) ?: return "Kein Archiv: $name"
        }
        for (part in dao.completedParts(item.packageId, base)) {
            val partName = part.fileName ?: continue
            val local = File(downloadDir, partName)
            if (!local.isFile && !restoreArchive(part, local)) {
                return "Archivteil nicht mehr vorhanden: $partName"
            }
        }
        val primary = Extractor.findPrimaryVolume(downloadDir, base)
            ?: return "Erstes Archiv-Teil fehlt"
        if (ExtractionRegistry.isActive(base)) return "Wird bereits entpackt"
        val set = ArchiveSet(id, item.packageId, base)
        completionMutex.withLock {
            // Laufende Teile gehoeren nicht ins Set: sie wuerden auf EXTRACTING
            // gesetzt und nach dem Entpacken als "fertig" markiert, obwohl sie noch laden
            if (dao.pendingLoadingParts(item.packageId, base, id) > 0) {
                return "Archiv unvollständig – weitere Teile werden noch geladen"
            }
            dao.setExtractingSet(archiveSetIds(set))
        }
        launchExtraction(set, primary, File(downloadDir, name))
        return null
    }

    /**
     * Fertige Archivdatei in den App-Ordner zurueckholen: aus dem gemerkten
     * Pfad, dem eigenen Zielordner (SAF) oder Downloads/JDAndroid (MediaStore).
     */
    private suspend fun restoreArchive(item: DownloadItem, dest: File): Boolean {
        val name = item.fileName ?: return false
        item.localPath?.let { path ->
            val f = File(path)
            if (f.isFile && f.path != dest.path) {
                return runCatching { f.copyTo(dest, overwrite = true); true }.getOrDefault(false)
            }
        }
        return storage.restoreExported(name, dest)
    }

    /**
     * Entpacken, exportieren, Set aktualisieren - immer nur eines gleichzeitig,
     * nicht abbrechbar. [setIds] sind die beim Start erfassten Kennungen des
     * Sets; sie gelten bis zum Ende, auch wenn Zeilen zwischendurch
     * verschwinden ("Links nach dem Entpacken entfernen", Loeschen durch den
     * Nutzer) - eine erneute Abfrage faende sie nicht mehr, und ihre
     * Bus-Eintraege blieben liegen.
     */
    private suspend fun extractAndExport(set: ArchiveSet, setIds: List<Long>, primary: File, archiveFile: File) =
        withContext(NonCancellable) {
            val (id, packageId, base) = set
            extractLimiter.withPermit {
                var finished = false
                var failure: String? = null
                try {
                    // Immer in einen Unterordner mit dem Paketnamen (wie im
                    // JDownloader); ohne Paket der Archivname
                    val folder = packageFolder(packageId) ?: base
                    val extractDir = File(storage.downloadDir(), folder)
                    // Fortschritt in Prozent fuer alle Teile - nur in den Bus,
                    // der je Eintrag drosselt; die Datenbank sieht nur Start und Ende
                    val listener = Extractor.ProgressListener { done, total ->
                        if (total <= 0) return@ProgressListener
                        val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                        val now = clock.nowMillis()
                        setIds.forEach { ProgressBus.update(it, LiveProgress(extractPercent = percent), now) }
                    }
                    Extractor.extract(
                        primary, extractDir,
                        settings.currentPasswords(),
                        settings.currentExtractExcludes(),
                        flat = settings.currentFlatExtract(),
                        progress = listener
                    )
                    val exportedPath = storage.exportDirectory(extractDir, folder)
                    if (settings.currentDeleteArchive()) {
                        storage.downloadDir().listFiles()
                            ?.filter { ArchiveNames.archiveBase(it.name) == base }
                            ?.forEach { it.delete() }
                    }
                    dao.byId(id)?.let { AccountRefresher.refreshHoster(app, it.hosterId) }
                    // Alle Teile des Sets zurueck auf fertig, mit dem Zielordner
                    dao.completeExtractingSet(setIds, exportedPath, null)
                    finished = true
                    if (settings.currentRemoveLinksAfterExtract()) {
                        removeExtractedEntries(set)
                    }
                } catch (e: Throwable) {
                    // Auch Error (OutOfMemoryError, UnsatisfiedLinkError des nativen
                    // 7-Zip): sonst bleibt das Set fuer immer EXTRACTING
                    failure = e.message ?: e.javaClass.simpleName
                } finally {
                    if (!finished) {
                        runCatching { dao.completeExtractingSet(setIds, archiveFile.absolutePath, failure) }
                    }
                    runCatching { ProgressBus.removeAll(setIds) }
                }
            }
        }

    /**
     * Wie im JDownloader ("Links nach dem Entpacken entfernen"): alle fertigen
     * Eintraege dieses Archivs (alle Teile) verschwinden aus der Liste, leere
     * Pakete werden aufgeraeumt. Die entpackten Dateien bleiben natuerlich.
     */
    private suspend fun removeExtractedEntries(set: ArchiveSet) {
        dao.deleteExtractedSet(set.packageId, set.base, set.id)
        app.db.packageDao().deleteEmpty()
    }

    private suspend fun markCompleted(id: Long, path: String?, note: String?) {
        // Traffic-Stand des Hosters nachladen (gedrosselt), damit die
        // Kontenansicht den Verbrauch zeigt
        dao.byId(id)?.let { AccountRefresher.refreshHoster(app, it.hosterId) }
        // Bedingt: nur wenn der Eintrag noch laeuft/entpackt (nicht zwischenzeitlich
        // pausiert oder geloescht)
        dao.completeIfActive(id, path, note)
    }

    /** Ordnername aus dem Paketnamen, dateisystemtauglich; null ohne Paket. */
    private suspend fun packageFolder(packageId: Long?): String? {
        val name = app.db.packageDao().byId(packageId ?: return null)?.name ?: return null
        return FileNames.clean(name)?.trimEnd('.')?.let { FileNames.limitLength(it, 120) }?.ifBlank { null }
    }

    internal companion object {
        /** Hinweis an fertigen Archiv-Teilen, solange andere Teile noch laden. */
        const val WAITING_NOTE = "Warte auf weitere Archiv-Teile"
    }
}
