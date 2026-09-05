package com.jdandroid.engine

import com.jdandroid.JdApp
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.Clock
import com.jdandroid.core.FileNames
import com.jdandroid.core.LiveProgress
import com.jdandroid.core.ProgressBus
import com.jdandroid.core.Texts
import com.jdandroid.data.AccountRefresher
import com.jdandroid.data.DownloadDao
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.SettingsRepository
import com.jdandroid.data.renameFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Completes finished downloads: plain files move to their target, archives
 * wait for their parts and are extracted and exported when enabled. An
 * archive set (all volumes of one archive within a package) is always set to
 * EXTRACTING and completed as a whole; [ExtractionRegistry] tracks running
 * archives process-wide.
 */
internal class ArchiveCoordinator(
    private val app: JdApp,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val storage: StorageTarget,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val onStateChanged: () -> Unit,
    /** Called after each extraction so the engine can pump again. */
    private val onExtractionFinished: () -> Unit
) {
    /**
     * Serialises download completion so two parts of the same archive that
     * finish at once cannot each see the other as still pending (which would
     * skip extraction). The engine also holds it while pausing or requeueing
     * so a completion in progress runs to the end.
     */
    val completionMutex = Mutex()

    private val extractLimiter = Semaphore(1)
    private val extracting get() = ExtractionRegistry.count

    /** Running extractions across all service instances. */
    val activeCount: Int get() = extracting.get()

    /** Triggering entry, its package and the archive key. */
    private data class ArchiveSet(val id: Long, val packageId: Long?, val base: String) {
        /** Registry key: same base in different packages are different sets. */
        val key get() = setKey(packageId, base)
    }

    fun archiveDir(packageId: Long?): File = archiveDir(storage.downloadDir(), packageId)

    /**
     * Completes a download: archives are extracted once all parts are present
     * (if enabled), everything else is exported directly. NonCancellable: a
     * pause during export must not let the copy finish while the status change
     * fails, which would leave a paused entry without its part file.
     */
    suspend fun completeDownload(id: Long, temp: File, originalName: String) = withContext(NonCancellable) {
        var fileName = originalName
        var base = ArchiveNames.archiveBase(fileName)
        if (base == null) {
            // "name part1 rar": the hoster replaced dots with spaces
            val repaired = ArchiveNames.repairName(fileName)
            if (ArchiveNames.archiveBase(repaired) != null) {
                fileName = repaired
                dao.renameFile(id, fileName)
                base = ArchiveNames.archiveBase(fileName)
            }
        }
        if (base == null) {
            // Archive content without an archive extension: add it from the magic bytes
            Extractor.sniffExtension(temp)?.let { ext ->
                fileName = "$fileName.$ext"
                dao.renameFile(id, fileName)
                base = ArchiveNames.archiveBase(fileName)
            }
        }
        val autoExtract = settings.currentAutoExtract()

        if (!autoExtract || base == null) {
            // Move and status change under the completion lock: pause() and a
            // network change wait instead of setting PAUSED/QUEUED on an entry
            // whose part file is already gone.
            val packageId = completionMutex.withLock {
                // Paused or requeued while finishing: keep the .part file for resuming
                val row = dao.byId(id) ?: return@withLock null
                if (row.status != DownloadStatus.RUNNING) return@withLock null
                val path = storage.finish(temp, fileName)
                markCompleted(id, path, null)
                row.packageId
            }
            // A waiting archive set of the same package may be complete now
            retryWaitingSets(packageId)
            return@withContext
        }

        val (shouldExtract, archiveFile) = completionMutex.withLock {
            val row = dao.byId(id) ?: return@withContext
            if (row.status != DownloadStatus.RUNNING) return@withContext
            val packageId = row.packageId
            val archiveFile = File(archiveDir(packageId), fileName)
            // Volumes live under their real name in the package folder so multipart sets find each other
            if (temp.path != archiveFile.path) {
                archiveFile.delete()
                temp.renameTo(archiveFile)
            }
            val set = ArchiveSet(id, packageId, base!!)
            val pending = dao.pendingActiveParts(packageId, set.base, id) > 0
            if (pending || ExtractionRegistry.isActive(set.key)) {
                markCompleted(id, archiveFile.absolutePath, WAITING_NOTE)
                null to archiveFile
            } else {
                // The whole set shows "extracting", not only the part that finished last
                dao.setExtractingSet(dao.archiveSetIds(packageId, set.base, id))
                set to archiveFile
            }
        }
        if (shouldExtract == null) return@withContext

        startExtraction(shouldExtract, archiveFile)
    }

    /**
     * Extracts a complete set that is already EXTRACTING. Without a first
     * volume the whole set goes back to completed, otherwise the other parts
     * would stay EXTRACTING forever.
     */
    private suspend fun startExtraction(set: ArchiveSet, archiveFile: File) {
        val primary = Extractor.findPrimaryVolume(archiveDir(set.packageId), set.base)
        if (primary == null) {
            dao.byId(set.id)?.let { AccountRefresher.refreshHoster(app, it.hosterId) }
            dao.completeExtractingSet(archiveSetIds(set), archiveFile.absolutePath, Texts.t("engine_first_volume_missing_not_extracted"))
            return
        }
        // Own job: frees the download slot at once instead of blocking the queue behind a large RAR
        launchExtraction(set, primary, archiveFile)
    }

    /**
     * Re-checks completed parts carrying [WAITING_NOTE] in the package: when the
     * last pending entry gets a name or completes as a non-archive, nothing
     * else would trigger the extraction.
     */
    suspend fun retryWaitingSets(packageId: Long?) = withContext(NonCancellable) {
        if (packageId == null) return@withContext
        val ready = completionMutex.withLock {
            dao.waitingParts(packageId, WAITING_NOTE).groupBy { it.archiveKey!! }
                .mapNotNull { (base, parts) ->
                    val self = parts.first()
                    val set = ArchiveSet(self.id, packageId, base)
                    if (dao.pendingActiveParts(packageId, base, self.id) > 0 || ExtractionRegistry.isActive(set.key)) {
                        return@mapNotNull null
                    }
                    dao.setExtractingSet(dao.archiveSetIds(packageId, base, self.id))
                    set to File(archiveDir(packageId), self.fileName!!)
                }
        }
        ready.forEach { (set, archiveFile) -> startExtraction(set, archiveFile) }
    }

    /** Completed or extracting parts of the set including the trigger, see [com.jdandroid.data.ArchiveSets.SET_IDS]. */
    private suspend fun archiveSetIds(set: ArchiveSet): List<Long> =
        dao.archiveSetIds(set.packageId, set.base, set.id)

    private suspend fun launchExtraction(set: ArchiveSet, primary: File, archiveFile: File) {
        val setIds = archiveSetIds(set)
        // Already running (possibly in an earlier service instance): that instance completes the set
        if (!ExtractionRegistry.start(set.key, setIds)) return
        extracting.incrementAndGet()
        onStateChanged()
        // ATOMIC: on an already cancelled scope a default start never runs the
        // body, not even finally - registry and counter would leak for good
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                extractAndExport(set, setIds, primary, archiveFile)
            } finally {
                ExtractionRegistry.finish(set.key, setIds)
                extracting.decrementAndGet()
                onStateChanged()
                onExtractionFinished()
            }
        }
    }

    /**
     * Extracts a completed download on demand, fetching exported parts of the
     * set back into the app directory if needed. Returns an error message, or
     * null when extraction was started.
     */
    suspend fun extractNow(id: Long): String? {
        // Count as active before fetching files: otherwise a freshly started
        // service considers itself idle, stops, and the next instance requeues
        // the EXTRACTING entries.
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
        val item = dao.byId(id) ?: return Texts.t("engine_entry_not_found")
        if (item.status == DownloadStatus.EXTRACTING) return Texts.t("engine_already_extracting")
        if (item.status != DownloadStatus.COMPLETED) return Texts.t("engine_only_completed_extractable")
        var name = item.fileName ?: return Texts.t("engine_file_name_unknown")
        var base = ArchiveNames.archiveBase(name)
        val downloadDir = archiveDir(item.packageId)
        if (base == null) {
            // "name part1 rar": rename all parts of the set (local or fetched back)
            // and fix the database. archiveKey is derived from the repaired name,
            // so it finds these parts too.
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
            // Possibly an archive without a matching extension
            val local = File(downloadDir, name).takeIf { it.isFile }
                ?: run { val f = File(downloadDir, name); if (restoreArchive(item, f)) f else null }
            val ext = local?.let { Extractor.sniffExtension(it) } ?: return Texts.t("engine_not_an_archive", name)
            val renamed = File(downloadDir, "$name.$ext")
            local.renameTo(renamed)
            name = renamed.name
            dao.renameFile(id, name)
            base = ArchiveNames.archiveBase(name) ?: return Texts.t("engine_not_an_archive", name)
        }
        for (part in dao.completedParts(item.packageId, base)) {
            val partName = part.fileName ?: continue
            val local = File(downloadDir, partName)
            if (!local.isFile && !restoreArchive(part, local)) {
                return Texts.t("engine_archive_part_missing", partName)
            }
        }
        val primary = Extractor.findPrimaryVolume(downloadDir, base)
            ?: return Texts.t("engine_first_volume_missing")
        val set = ArchiveSet(id, item.packageId, base)
        if (ExtractionRegistry.isActive(set.key)) return Texts.t("engine_already_extracting")
        completionMutex.withLock {
            // Loading parts must not join the set: they would be marked EXTRACTING
            // and then completed while still transferring
            if (dao.pendingLoadingParts(item.packageId, base, id) > 0) {
                return Texts.t("engine_archive_incomplete_loading")
            }
            dao.setExtractingSet(archiveSetIds(set))
        }
        launchExtraction(set, primary, File(downloadDir, name))
        return null
    }

    /** Fetches a completed archive file back into the package folder from its stored path or the export target. */
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
     * Extracts, exports and updates the set; one at a time, NonCancellable.
     * [setIds] were captured at start and stay valid to the end even if rows
     * disappear meanwhile (user deletion, "remove links after extraction"):
     * a fresh query would miss them and leave their bus entries behind.
     */
    private suspend fun extractAndExport(set: ArchiveSet, setIds: List<Long>, primary: File, archiveFile: File) =
        withContext(NonCancellable) {
            val (id, packageId, base) = set
            extractLimiter.withPermit {
                var finished = false
                var failure: String? = null
                try {
                    // Always a subfolder named after the package; the archive name without a package
                    val folder = packageFolder(packageId) ?: base
                    val extractDir = File(storage.downloadDir(), folder)
                    // Percentage for all parts goes to the bus only; the database sees start and end
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
                        archiveDir(packageId).listFiles()
                            ?.filter { ArchiveNames.archiveBase(it.name) == base }
                            ?.forEach { it.delete() }
                    }
                    dao.byId(id)?.let { AccountRefresher.refreshHoster(app, it.hosterId) }
                    dao.completeExtractingSet(setIds, exportedPath, null)
                    finished = true
                    if (settings.currentRemoveLinksAfterExtract()) {
                        removeExtractedEntries(set)
                    }
                } catch (e: Throwable) {
                    // Errors too (OutOfMemoryError, UnsatisfiedLinkError of native 7-Zip),
                    // otherwise the set stays EXTRACTING forever
                    failure = e.message ?: e.javaClass.simpleName
                } finally {
                    if (!finished) {
                        runCatching { dao.completeExtractingSet(setIds, archiveFile.absolutePath, failure) }
                    }
                    runCatching { ProgressBus.removeAll(setIds) }
                }
            }
        }

    /** Removes the completed entries of the archive from the list and cleans up empty packages. */
    private suspend fun removeExtractedEntries(set: ArchiveSet) {
        dao.deleteExtractedSet(set.packageId, set.base, set.id)
        app.db.packageDao().deleteEmpty()
    }

    private suspend fun markCompleted(id: Long, path: String?, note: String?) {
        dao.byId(id)?.let { AccountRefresher.refreshHoster(app, it.hosterId) }
        // Only if the entry is still running or extracting (not paused or deleted meanwhile)
        dao.completeIfActive(id, path, note)
    }

    /** File-system-safe folder name from the package name; null without a package. */
    private suspend fun packageFolder(packageId: Long?): String? {
        val name = app.db.packageDao().byId(packageId ?: return null)?.name ?: return null
        return FileNames.clean(name)?.trimEnd('.')?.let { FileNames.limitLength(it, 120) }?.ifBlank { null }
    }

    internal companion object {
        /**
         * Note on completed archive parts while other parts are still loading.
         * An untranslated code compared in SQL ([com.jdandroid.data.ArchiveSets.WAITING_PARTS]);
         * the UI translates it (`downloads_waiting_for_parts`).
         */
        const val WAITING_NOTE = DownloadNotes.WAITING_PARTS

        /** [ExtractionRegistry] key of a set; entries without a package count as package 0. */
        fun setKey(packageId: Long?, base: String): String = "${packageId ?: 0}/$base"

        /**
         * Archive volumes are kept per package so that same-named sets of two
         * packages never mix; ".archives" can never collide with a package folder
         * ([FileNames.clean] strips leading dots).
         */
        fun archiveDir(downloadDir: File, packageId: Long?): File =
            File(downloadDir, ".archives/${packageId ?: 0}").apply { mkdirs() }
    }
}
