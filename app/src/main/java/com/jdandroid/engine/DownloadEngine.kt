package com.jdandroid.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import com.jdandroid.JdApp
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.Clock
import com.jdandroid.core.FileNames
import com.jdandroid.core.FreeMode
import com.jdandroid.core.LiveProgress
import com.jdandroid.core.ProgressBus
import com.jdandroid.core.Texts
import com.jdandroid.core.formatBytes
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.PackageNaming
import com.jdandroid.data.renameFile
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.HosterRegistry
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.WaitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Runs the downloads: resolves links, transfers files with range resume and
 * reports progress via [ProgressBus]. Completion, extraction and export are
 * handled by [ArchiveCoordinator], storage locations by [StorageTarget], the
 * free-mode flow by [FreeFlow].
 *
 * Only state changes (with the last byte count) and a byte-count checkpoint
 * at most every [SAVE_MS] reach the database; live values (bytes, speed,
 * extraction percent) exist in the bus only.
 */
class DownloadEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Monotonic clock for measurements and throttling; tests can advance it. */
    private val clock: Clock = Clock.SYSTEM,
    private val onStateChanged: () -> Unit
) {
    private val app = context.applicationContext as JdApp
    private val dao = app.db.downloadDao()
    private val accountDao = app.db.accountDao()

    // ConcurrentHashMap: size() is read by the service thread without the mutex
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val mutex = Mutex()

    @Volatile
    private var lastNotify = clock.nowMillis() - NOTIFY_MS

    private val storage = StorageTarget(context, app.settings)
    private val freeFlow = FreeFlow(dao, accountDao, app.settings)
    private val archives = ArchiveCoordinator(
        app, dao, app.settings, storage, scope, clock, onStateChanged
    ) { scope.launch { pump() } }
    /** See [ArchiveCoordinator.completionMutex]. */
    private val completionMutex get() = archives.completionMutex

    /**
     * Start gate: pump() waits until the service has requeued stale entries.
     * Otherwise an early pump() (network callback) could start an entry that
     * requeueRunning() then requeues, running the same download twice.
     */
    private val startGate = CompletableDeferred<Unit>()

    private val limiter = SpeedLimiter(clock)

    init {
        scope.launch {
            app.settings.speedLimitMbit.collect {
                limiter.limitBps = com.jdandroid.data.SettingsRepository.mbitToBytesPerSecond(it)
            }
        }
    }

    /** Running downloads plus running extractions. */
    val activeCount: Int get() = jobs.size + archives.activeCount

    fun markReady() { startGate.complete(Unit) }

    /** Nothing runs and nothing is due; under the lock so pump() cannot interleave. */
    suspend fun isIdle(): Boolean = mutex.withLock {
        // Entries held for a captcha need no running service: solving it restarts the service
        jobs.isEmpty() && archives.activeCount == 0 &&
            dao.queuedCountDue(System.currentTimeMillis() + FreeMode.USER_ACTION_HORIZON_MS) == 0
    }

    val totalSpeedBps: Long get() = ProgressBus.totalSpeedBps()

    /** Downloaded bytes of all open entries: live bus values for running ones, database for the rest. */
    suspend fun openDownloadedBytes(): Long {
        val live = ProgressBus.state.value
        val liveBytes = live.values.sumOf { it.downloadedBytes.coerceAtLeast(0) }
        return dao.openDownloadedBytesExcept(live.keys.toList() + listOf(-1L)) + liveBytes
    }

    private fun notifyProgress() {
        val now = clock.nowMillis()
        if (now - lastNotify >= NOTIFY_MS) {
            lastNotify = now
            onStateChanged()
        }
    }

    /** True when "Wi-Fi only" is set and the active network is metered. */
    private fun blockedByMeteredNetwork(wifiOnly: Boolean): Boolean {
        if (!wifiOnly) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts queued downloads while slots are free and arms the retry timer. */
    suspend fun pump() {
        startGate.await()
        if (blockedByMeteredNetwork(app.settings.currentWifiOnly())) {
            onStateChanged()
            return
        }
        val max = app.settings.currentMaxConcurrent()
        mutex.withLock {
            while (jobs.size < max) {
                // Exclude running ids: never the same download twice
                val running = jobs.keys.toList() + listOf(-1L)
                val next = dao.nextQueued(System.currentTimeMillis(), running) ?: break
                dao.setStatus(next.id, DownloadStatus.RUNNING)
                jobs[next.id] = scope.launch { run(next.id) }
            }
        }
        armRetryTimer()
        onStateChanged()
    }

    /**
     * On a metered network with "Wi-Fi only" set, running downloads go back to
     * the queue and restart automatically when Wi-Fi returns; otherwise pumps.
     */
    suspend fun onNetworkChanged() {
        if (blockedByMeteredNetwork(app.settings.currentWifiOnly())) {
            val running = mutex.withLock {
                val copy = jobs.toMap()
                jobs.clear()
                copy
            }
            running.values.forEach { it.cancel() }
            ProgressBus.removeAll(running.keys)
            // Under the completion lock: a job in its NonCancellable completion
            // (file being moved) must not be requeued any more
            completionMutex.withLock { running.keys.forEach { dao.requeueIfRunning(it) } }
            onStateChanged()
        } else {
            pump()
        }
    }

    suspend fun pause(id: Long) {
        cancelAndPause(listOf(id)) { dao.pauseIfActive(id) }
        pump()
    }

    /**
     * Cancels the jobs of [ids] and runs [pauseRows] (RUNNING/QUEUED -> PAUSED)
     * under [mutex], so the finally-pump of a cancelled job cannot start the
     * next entry of the batch in between. Only cancelled jobs leave the bus
     * (an extracting entry keeps its percentage); [pauseRows] runs under the
     * completion lock so a finishing transfer is never paused after its file
     * was moved.
     */
    private suspend fun cancelAndPause(ids: Collection<Long>, pauseRows: suspend () -> Unit) {
        mutex.withLock {
            ids.forEach { id -> jobs.remove(id)?.let { it.cancel(); ProgressBus.remove(id) } }
            completionMutex.withLock { pauseRows() }
        }
    }

    suspend fun cancelAndDelete(id: Long) {
        mutex.withLock { jobs.remove(id) }?.cancel()
        ProgressBus.remove(id)
        FreeDownloads.forget(id)
        val item = dao.byId(id)
        dao.delete(id)
        item?.let {
            tempFile(it).delete()
            // Remove archive volumes too unless another entry of the package
            // references the same archive; they would linger invisibly otherwise
            val key = it.archiveKey
            if (key != null && dao.countByArchiveKey(it.packageId, key) == 0) {
                archives.archiveDir(it.packageId).listFiles()
                    ?.filter { f -> f.isFile && ArchiveNames.archiveBase(f.name) == key }
                    ?.forEach { f -> f.delete() }
            }
            // The deleted entry may have been the last one a waiting archive set was blocked by
            archives.retryWaitingSets(it.packageId)
        }
        pump()
    }

    /** Pauses all entries of a package with one pump at the end. */
    suspend fun pausePackage(packageId: Long) {
        val ids = dao.byPackage(packageId).map { it.id }
        cancelAndPause(ids) { dao.pauseActiveInPackage(packageId) }
        pump()
    }

    /**
     * Deletes a package with its entries and files. Jobs are cancelled and the
     * rows removed under [mutex], so no entry of the package gets started in
     * between; the archive folder belongs to this package alone.
     */
    suspend fun deletePackage(packageId: Long) {
        val items = mutex.withLock {
            val items = dao.byPackage(packageId)
            items.forEach { jobs.remove(it.id)?.cancel(); FreeDownloads.forget(it.id) }
            ProgressBus.removeAll(items.map { it.id })
            app.db.withTransaction {
                dao.deletePackageItems(packageId)
                app.db.packageDao().delete(packageId)
            }
            items
        }
        items.forEach { tempFile(it).delete() }
        archives.archiveDir(packageId).deleteRecursively()
        pump()
    }

    suspend fun pauseAll() {
        // Pause the queue first: cancelled jobs call pump() in their finally
        // and would start the next entries right away.
        dao.pauseQueued()
        val running = mutex.withLock {
            val copy = jobs.toMap()
            jobs.clear()
            copy
        }
        running.values.forEach { it.cancel() }
        ProgressBus.removeAll(running.keys)
        // Extracting entries stay EXTRACTING (they continue NonCancellable)
        completionMutex.withLock { running.keys.forEach { dao.pauseIfActive(it) } }
        onStateChanged()
    }

    private fun tempFile(item: DownloadItem): File {
        val name = item.fileName ?: "download-${item.id}"
        return File(storage.downloadDir(), "${item.id}-$name.part")
    }

    private suspend fun run(id: Long) {
        try {
            val item = dao.byId(id) ?: return
            // Archive already complete in the package folder (e.g. paused during extraction): do not reload
            item.fileName?.let { name ->
                val existing = File(archives.archiveDir(item.packageId), name)
                if (item.fileSize > 0 && existing.isFile && existing.length() == item.fileSize) {
                    archives.completeDownload(id, existing, name)
                    return
                }
            }
            val hoster = HosterRegistry.byId(item.hosterId)
                ?: throw HosterException(Texts.t("engine_unknown_hoster"), true)
            val resolved = freeFlow.resolve(id, item, hoster)

            var current = dao.byId(id) ?: return
            val resolvedName = resolved.fileName?.let { FileNames.sanitize(it) }
            if (resolvedName != null && FileNames.preferName(current.fileName, resolvedName)) {
                current = adoptFileName(current, resolvedName)
                PackageNaming.refineAutoName(app.db, current.packageId)
            }
            download(current, resolved.directUrl, resolved.hash, resolved.headers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: WaitException) {
            freeFlow.scheduleWait(id, e.seconds, e.message)
        } catch (e: CaptchaRequiredException) {
            freeFlow.holdForCaptcha(id, e)
        } catch (e: HosterException) {
            if (e.permanent) {
                fail(id, e.message)
            } else {
                handleTransientFailure(id, e.message ?: Texts.t("engine_generic_error"))
            }
        } catch (e: com.jdandroid.data.Secrets.SecretsException) {
            // Credentials cannot be decrypted: retrying is pointless
            fail(id, e.message)
        } catch (e: IllegalArgumentException) {
            // Unusable download URL (e.g. relative link)
            fail(id, Texts.t("engine_invalid_download_url", e.message ?: ""))
        } catch (e: Exception) {
            // Cancellation (pause/delete) arrives from OkHttp as IOException("Canceled")
            // and must not count as a transient failure
            coroutineContext.ensureActive()
            handleTransientFailure(id, e.message ?: e.javaClass.simpleName)
        } finally {
            // NonCancellable: withLock throws immediately in a cancelled coroutine
            // under contention. transfer() clears its own bus entry, which may
            // belong to the extraction after completion.
            withContext(NonCancellable) { mutex.withLock { jobs.remove(id) } }
            scope.launch { pump() }
        }
    }

    /**
     * Marks the entry FAILED. A failed entry no longer counts as pending, so
     * archive sets of its package that waited for it are checked again.
     */
    private suspend fun fail(id: Long, message: String?) {
        val packageId = dao.byId(id)?.packageId
        dao.setStatus(id, DownloadStatus.FAILED, message)
        archives.retryWaitingSets(packageId)
    }

    /**
     * Schedules a retry with exponential backoff; after [MAX_ATTEMPTS] the
     * download fails. The retry itself is started by the timer from pump().
     */
    private suspend fun handleTransientFailure(id: Long, message: String) {
        val item = dao.byId(id) ?: return
        val attempts = item.attempts + 1
        if (attempts > MAX_ATTEMPTS) {
            fail(id, Texts.t("engine_gave_up", message, MAX_ATTEMPTS))
            return
        }
        val backoff = backoffMillis(attempts)
        dao.scheduleRetry(
            id, attempts, System.currentTimeMillis() + backoff,
            Texts.t("engine_retry_scheduled", message, attempts, MAX_ATTEMPTS, backoff / 1000)
        )
    }

    /** Pending timer for the next retryAt and its time; both guarded by [timerLock]. */
    private var retryTimer: Job? = null
    private var retryTimerAt = Long.MAX_VALUE
    private val timerLock = Any()

    /**
     * Arms a timer for the smallest future retryAt (free wait, backoff). The
     * time is read from the database rather than kept in a delay(): if the
     * process dies during an hours-long wait, the new service calls pump()
     * only once and nothing would trigger the entry after the wait. Only one
     * timer runs; an earlier time replaces it. Captcha entries beyond the
     * horizon are ignored. retryAt is a persisted wall-clock timestamp, hence
     * the wall clock here.
     */
    private suspend fun armRetryTimer() {
        val now = System.currentTimeMillis()
        val next = dao.nextRetryAt(now, now + FreeMode.USER_ACTION_HORIZON_MS) ?: return
        synchronized(timerLock) {
            if (retryTimer?.isActive == true && retryTimerAt <= next) return
            retryTimer?.cancel()
            retryTimerAt = next
            retryTimer = scope.launch {
                kotlinx.coroutines.delay((next - now + RETRY_TIMER_SLACK_MS).coerceAtLeast(0))
                // Deregister before pump() arms the next timer, or pump() would
                // take this still-running job for the current one
                synchronized(timerLock) {
                    if (retryTimer === coroutineContext[Job]) {
                        retryTimer = null
                        retryTimerAt = Long.MAX_VALUE
                    }
                }
                pump()
            }
        }
    }

    private suspend fun download(
        item: DownloadItem,
        directUrl: String,
        expectedHash: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        var target = tempFile(item)
        var offset = if (target.exists()) target.length() else 0L

        val builder = Request.Builder()
            .url(directUrl)
            .header("User-Agent", Http.USER_AGENT)
            // No gzip: Content-Length and the completeness check would be lost
            .header("Accept-Encoding", "identity")
        // Hoster headers (free mode: Cookie, Referer); may override the User-Agent
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        // Cut the connection at once on pause/delete, or the cancellation takes
        // effect only after the next socket read (up to 60 s). invokeOnCompletion
        // fires only in the job's final state, i.e. after the blocking read
        // returned anyway; the watcher reacts to the "cancelling" transition.
        val call = Http.client.newCall(builder.build())
        val vanished = !coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { resp -> transfer(resp, item, target, offset) { target = it } }
            } finally {
                watcher.cancel()
            }
        }
        if (vanished) return

        if (expectedHash != null) verifyHash(target, expectedHash)

        val finalName = dao.byId(item.id)?.fileName ?: target.name.removeSuffix(".part")
        archives.completeDownload(item.id, target, finalName)
        onStateChanged()
    }

    /**
     * Classifies the response and writes the body to [initialTarget]. Returns
     * false if the entry was deleted meanwhile. [onTarget] reports a renamed
     * part file (the name arrived with the response).
     */
    private suspend fun transfer(
        resp: okhttp3.Response,
        item: DownloadItem,
        initialTarget: File,
        initialOffset: Long,
        onTarget: (File) -> Unit
    ): Boolean {
        var target = initialTarget
        var offset = initialOffset
        when (
            classifyResponse(
                resp.code, resp.header("Content-Type"), resp.header("Content-Disposition"),
                offset, item.fileSize
            )
        ) {
            ResponseKind.AlreadyComplete -> {
                dao.saveProgress(item.id, offset, offset)
                return true
            }
            ResponseKind.RestartMismatch -> {
                target.delete()
                throw HosterException(Texts.t("engine_part_size_mismatch"))
            }
            ResponseKind.HttpError -> throw HosterException(Texts.t("engine_http_error", resp.code))
            // Never store HTML as file content: the page would end up in the .part
            // and be glued to the real remainder on resume
            ResponseKind.HtmlPage -> throw HosterException(
                Texts.t("engine_html_instead_of_file", resp.request.url.host)
            )
            ResponseKind.RangeIgnored -> {
                target.delete()
                offset = 0
            }
            ResponseKind.Continue -> Unit
        }
        val body = resp.body // never null since OkHttp 5
        val total = transferTotal(body.contentLength(), offset, item.fileSize)

        // Check free space up front, or the download fails minutes later with
        // an unhelpful IO error
        val needed = if (total > 0) total - offset else 0
        val free = android.os.StatFs(storage.downloadDir().path).availableBytes
        if (needed > 0 && free in 0 until needed) {
            throw HosterException(
                Texts.t("engine_not_enough_space", formatBytes(needed), formatBytes(free)),
                permanent = true
            )
        }

        // The server-supplied name replaces a placeholder without an extension
        // (e.g. from the page title), otherwise the archive is never recognised
        var current = dao.byId(item.id) ?: return false
        val serverName = FileNames.fromDisposition(resp.header("Content-Disposition"))
        val candidate = when {
            current.fileName == null -> FileNames.fromResponse(resp.header("Content-Disposition"), resp.request.url)
            serverName != null && FileNames.preferName(current.fileName, serverName) -> serverName
            else -> null
        }
        if (candidate != null) {
            current = adoptFileName(current, candidate)
            target = tempFile(current)
            onTarget(target)
        }

        var written = offset
        val startedAt = clock.nowMillis()
        var lastSave = startedAt
        var attemptsReset = false
        dao.saveProgress(item.id, written, total)
        // Speed as a moving average over SPEED_WINDOW_MS; raw per-interval
        // values jump too much. Bytes and speed go to the bus every SAMPLE_MS,
        // the byte count is saved at most every SAVE_MS and when leaving the loop.
        val samples = ArrayDeque<Pair<Long, Long>>()
        samples.addLast(startedAt to written)
        try {
            body.byteStream().use { input ->
                java.io.FileOutputStream(target, offset > 0).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        limiter.throttle(read)
                        if (!attemptsReset && written - offset >= PROGRESS_RESET_BYTES) {
                            // Real progress: a flaky network must not give up a
                            // resumable download after MAX_ATTEMPTS drops
                            attemptsReset = true
                            dao.resetAttempts(item.id)
                        }
                        val now = clock.nowMillis()
                        if (now - samples.last().first >= SAMPLE_MS) {
                            samples.addLast(now to written)
                            while (samples.size > 2 && now - samples.first().first > SPEED_WINDOW_MS) {
                                samples.removeFirst()
                            }
                            val (t0, b0) = samples.first()
                            val speed = if (now > t0) (written - b0) * 1000 / (now - t0) else 0L
                            ProgressBus.update(item.id, LiveProgress(written, speed), now)
                            notifyProgress()
                            if (now - lastSave >= SAVE_MS) {
                                dao.saveProgress(item.id, written, total)
                                lastSave = now
                            }
                        }
                        kotlinx.coroutines.yield()
                    }
                }
            }
        } finally {
            // Save the last byte count even on cancel/failure so the list does
            // not jump back 30 s (resume uses the .part size anyway), then drop
            // the live values: the database is authoritative again
            withContext(NonCancellable) {
                dao.saveProgress(item.id, written, total)
                ProgressBus.remove(item.id)
            }
        }
        if (total > 0 && written < total) {
            throw IOException(Texts.t("engine_download_incomplete", formatBytes(written), formatBytes(total)))
        }
        return true
    }

    /** See [ArchiveCoordinator.extractNow]; waits for the start gate first. */
    suspend fun extractNow(id: Long): String? {
        startGate.await()
        return archives.extractNow(id)
    }

    /** On a hash mismatch the file is discarded and the download retried as a transient failure. */
    private suspend fun verifyHash(file: File, expected: String) {
        val algorithm = when (expected.length) {
            32 -> "MD5"
            40 -> "SHA-1"
            64 -> "SHA-256"
            else -> return
        }
        val actual = hashFile(file, algorithm)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw HosterException(Texts.t("engine_hash_mismatch", algorithm))
        }
    }

    /** Stores the new file name and renames an existing part file along with it. */
    private suspend fun adoptFileName(item: DownloadItem, name: String): DownloadItem {
        val old = tempFile(item)
        val updated = item.copy(fileName = name)
        val renamed = tempFile(updated)
        if (old.path != renamed.path && old.exists()) old.renameTo(renamed)
        dao.renameFile(item.id, name)
        // With a known name this entry may no longer block a waiting archive
        // set of the package (e.g. readme.nfo instead of part3)
        archives.retryWaitingSets(item.packageId)
        return updated
    }

    internal companion object {
        const val MAX_ATTEMPTS = 5

        /** Wait before the [attempt]-th retry: 10 s, 20 s, ... capped at 5 min. */
        fun backoffMillis(attempt: Int): Long {
            val base = 10_000L shl (attempt - 1).coerceAtMost(5)
            return base.coerceAtMost(5 * 60_000L)
        }

        /** New progress after which the attempt counter is reset. */
        const val PROGRESS_RESET_BYTES = 4L * 1024 * 1024

        const val SAMPLE_MS = 500L

        const val SPEED_WINDOW_MS = 5_000L

        /** Minimum interval between byte-count checkpoints in the database. */
        const val SAVE_MS = 30_000L

        /** Slack on the retry timer so nextQueued() definitely returns the entry. */
        const val RETRY_TIMER_SLACK_MS = 500L

        const val NOTIFY_MS = 1_000L
    }
}

/**
 * Hex digest of [file]. Yields between chunks so a pause cancels the check
 * instead of letting it run to the end over a multi-GiB file.
 */
internal suspend fun hashFile(file: File, algorithm: String): String {
    val digest = java.security.MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            kotlinx.coroutines.yield()
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
