package com.jdandroid

import com.jdandroid.core.Clock
import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.QueueDao
import com.jdandroid.data.SessionBytes
import com.jdandroid.engine.Completion
import com.jdandroid.engine.DownloadEngine
import com.jdandroid.engine.DownloadFiles
import com.jdandroid.engine.EngineSettings
import com.jdandroid.engine.ExportRetries
import com.jdandroid.engine.LinkResolver
import com.jdandroid.engine.NetworkPolicy
import com.jdandroid.engine.PackageStore
import com.jdandroid.hoster.AccountInfo
import com.jdandroid.hoster.AccountType
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.ResolvedLink
import com.jdandroid.hoster.WaitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The engine on the JVM against in-memory collaborators: slots, pause,
 * retries with backoff, hoster waits, Wi-Fi only and a real HTTP transfer
 * with resume. The fake rows follow the SQL of [com.jdandroid.data.DownloadDao].
 */
class DownloadEngineTest {

    private class TestClock(var wall: Long = 1_700_000_000_000L) : Clock {
        override fun nowNanos() = System.nanoTime()
        override fun wallMillis() = wall
    }

    private class FakeQueue : QueueDao {
        val rows = ConcurrentHashMap<Long, DownloadItem>()

        fun put(item: DownloadItem) { rows[item.id] = item }
        fun status(id: Long) = rows.getValue(id).status

        private fun update(id: Long, change: (DownloadItem) -> DownloadItem) {
            rows.computeIfPresent(id) { _, row -> change(row) }
        }

        private fun active(row: DownloadItem) = row.status == DownloadStatus.RUNNING || row.status == DownloadStatus.QUEUED

        override suspend fun byId(id: Long) = rows[id]
        override suspend fun byPackage(packageId: Long) = rows.values.filter { it.packageId == packageId }
        override suspend fun completedParts(packageId: Long?, key: String) =
            rows.values.filter { it.packageId == packageId && it.archiveKey == key && it.status == DownloadStatus.COMPLETED }
                .sortedBy { it.id }
        override suspend fun countByArchiveKey(packageId: Long?, key: String) =
            rows.values.count { it.packageId == packageId && it.archiveKey == key }
        override suspend fun delete(id: Long) { rows.remove(id) }
        override suspend fun deletePackageItems(packageId: Long) { rows.values.removeIf { it.packageId == packageId } }
        override suspend fun nextQueued(now: Long, running: List<Long>) =
            rows.values.filter { it.status == DownloadStatus.QUEUED && it.retryAt <= now && it.id !in running }
                .minByOrNull { it.addedAt }
        override suspend fun nextRetryAt(now: Long, horizon: Long) =
            rows.values.filter { it.status == DownloadStatus.QUEUED && it.retryAt > now && it.retryAt <= horizon }
                .minOfOrNull { it.retryAt }
        override suspend fun openIds() = rows.values.filter(::active).map { it.id }
        override suspend fun pauseActiveInPackage(packageId: Long) {
            rows.values.filter { it.packageId == packageId && active(it) }
                .forEach { update(it.id) { row -> row.copy(status = DownloadStatus.PAUSED, speedBps = 0) } }
        }
        override suspend fun pauseIfActive(id: Long) {
            update(id) { if (active(it)) it.copy(status = DownloadStatus.PAUSED, speedBps = 0) else it }
        }
        override suspend fun pauseQueued() {
            rows.values.filter { it.status == DownloadStatus.QUEUED }
                .forEach { update(it.id) { row -> row.copy(status = DownloadStatus.PAUSED) } }
        }
        override suspend fun queuedCountDue(before: Long) =
            rows.values.count { it.status == DownloadStatus.QUEUED && it.retryAt <= before }
        override suspend fun requeueForRedownload(id: Long): Int {
            val row = rows[id] ?: return 0
            if (row.status == DownloadStatus.RUNNING || row.status == DownloadStatus.EXTRACTING) return 0
            rows[id] = row.copy(
                status = DownloadStatus.QUEUED, errorMessage = null, attempts = 0, retryAt = 0,
                downloadedBytes = 0, localPath = null
            )
            return 1
        }
        override suspend fun requeueIfRunning(id: Long) {
            update(id) {
                if (it.status != DownloadStatus.RUNNING) it
                else it.copy(status = DownloadStatus.QUEUED, retryAt = 0, speedBps = 0, errorMessage = DownloadNotes.WAITING_WIFI)
            }
        }
        override suspend fun resetAttempts(id: Long) { update(id) { it.copy(attempts = 0) } }
        override suspend fun saveProgress(id: Long, bytes: Long, total: Long) {
            update(id) { it.copy(downloadedBytes = bytes, fileSize = total) }
        }
        override suspend fun scheduleRetry(id: Long, attempts: Int, retryAt: Long, error: String?) {
            update(id) {
                it.copy(status = DownloadStatus.QUEUED, attempts = attempts, retryAt = retryAt, errorMessage = error, speedBps = 0)
            }
        }
        override suspend fun sessionBytes(ids: List<Long>) =
            ids.mapNotNull { rows[it] }.map { SessionBytes(it.id, it.fileSize, it.downloadedBytes, it.status) }
        override suspend fun setStatus(id: Long, status: DownloadStatus, error: String?) {
            update(id) { it.copy(status = status, errorMessage = error) }
        }
        override suspend fun setFileName(id: Long, name: String, archiveKey: String?) {
            update(id) { it.copy(fileName = name, archiveKey = archiveKey) }
        }
    }

    /** Records completions and marks them COMPLETED, as the real coordinator does after the export. */
    private inner class FakeCompletion : Completion {
        val completed = CopyOnWriteArrayList<Pair<Long, ByteArray>>()
        val retriedPackages = CopyOnWriteArrayList<Long?>()
        override val completionMutex = Mutex()
        override val activeCount = 0
        override val exportingIds = emptyList<Long>()
        override fun archiveDir(packageId: Long?) = File(dir, "archives/${packageId ?: 0}")
        override fun markReplaceOnExtract(packageId: Long?, base: String) = Unit
        override suspend fun completeDownload(id: Long, temp: File, originalName: String) {
            completed += id to temp.readBytes()
            queue.setStatus(id, DownloadStatus.COMPLETED, null)
        }
        override suspend fun retryWaitingSets(packageId: Long?) { retriedPackages += packageId }
        override suspend fun extractNow(id: Long): String? = null
    }

    private inner class FakeResolver : LinkResolver {
        var resolve: suspend (DownloadItem) -> ResolvedLink = { error("no link") }
        val waits = CopyOnWriteArrayList<Pair<Long, Int>>()
        override suspend fun resolve(id: Long, item: DownloadItem, hoster: Hoster) = resolve(item)
        override suspend fun scheduleWait(id: Long, seconds: Int, reason: String?) {
            waits += id to seconds
            queue.scheduleRetry(id, 0, clock.wall + seconds * 1000L, reason)
        }
        override suspend fun holdForCaptcha(id: Long, e: CaptchaRequiredException) = Unit
    }

    private val hoster = object : Hoster {
        override val id = "test"
        override val displayName = "Test"
        override val accountType = AccountType.API_KEY
        override val accountHint = ""
        override val siteHosts = setOf("example.com")
        override fun matches(url: String) = true
        override suspend fun checkAccount(account: Account) = AccountInfo(valid = true, premium = true, statusText = "")
        override suspend fun resolve(url: String, account: Account?) = ResolvedLink(url)
    }

    private val dir: File = Files.createTempDirectory("engine").toFile()
    private val queue = FakeQueue()
    private val completion = FakeCompletion()
    private val resolver = FakeResolver()
    private val clock = TestClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maxConcurrent = 2
    private var wifiOnly = false
    private var metered = false

    private val engine = DownloadEngine(
        scope = scope,
        dao = queue,
        settings = object : EngineSettings {
            override suspend fun maxConcurrent() = maxConcurrent
            override suspend fun wifiOnly() = wifiOnly
            override val speedLimitMbit = flowOf(0.0)
        },
        files = object : DownloadFiles {
            override fun downloadDir() = dir
            override suspend fun deleteExported(displayPath: String) = false
        },
        archives = completion,
        exportRetry = object : ExportRetries {
            override val activeCount = 0
            override suspend fun run(forced: Boolean) = false
        },
        freeFlow = resolver,
        packages = object : PackageStore {
            override suspend fun refineAutoName(packageId: Long?) = Unit
            override suspend fun deletePackage(packageId: Long) = queue.deletePackageItems(packageId)
        },
        hosters = { id -> hoster.takeIf { it.id == id } },
        http = OkHttpClient(),
        secureLink = { it },
        network = NetworkPolicy { wifi -> wifi && metered },
        freeBytes = { Long.MAX_VALUE },
        clock = clock,
        onStateChanged = {}
    ).apply { markReady() }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun item(id: Long, size: Long = -1) = DownloadItem(
        id = id, url = "https://example.com/$id", hosterId = "test", packageId = 1,
        fileName = "file$id.bin", fileSize = size, status = DownloadStatus.QUEUED, addedAt = id
    )

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun startetNurSovieleWieErlaubtUndPauseGibtDenPlatzFrei() = runBlocking {
        val gate = CompletableDeferred<ResolvedLink>()
        resolver.resolve = { gate.await() }
        (1L..3L).forEach { queue.put(item(it)) }
        engine.pump()
        until { queue.rows.values.count { it.status == DownloadStatus.RUNNING } == 2 }
        assertEquals(listOf(DownloadStatus.RUNNING, DownloadStatus.RUNNING, DownloadStatus.QUEUED), (1L..3L).map(queue::status))

        engine.pause(1)
        until { queue.status(3) == DownloadStatus.RUNNING }
        assertEquals(DownloadStatus.PAUSED, queue.status(1))
        assertEquals(2, engine.activeCount)
    }

    @Test
    fun voruebergehenderFehlerWirdMitWachsendemAbstandWiederholt() = runBlocking {
        resolver.resolve = { throw HosterException("Server weg", permanent = false) }
        queue.put(item(1))
        engine.pump()
        until { queue.rows.getValue(1).attempts == 1 }
        val row = queue.rows.getValue(1)
        assertEquals(DownloadStatus.QUEUED, row.status)
        assertEquals(clock.wall + DownloadEngine.backoffMillis(1), row.retryAt)
        assertEquals(Texts.t("engine_retry_scheduled", "Server weg", 1, DownloadEngine.MAX_ATTEMPTS, 10), row.errorMessage)

        // The last allowed attempt fails for good
        queue.put(row.copy(attempts = DownloadEngine.MAX_ATTEMPTS, retryAt = 0))
        engine.pump()
        until { queue.status(1) == DownloadStatus.FAILED }
        assertEquals(Texts.t("engine_gave_up", "Server weg", DownloadEngine.MAX_ATTEMPTS), queue.rows.getValue(1).errorMessage)
        assertTrue(1L in completion.retriedPackages)
    }

    @Test
    fun dauerhafterFehlerOhneWiederholung() = runBlocking {
        resolver.resolve = { throw HosterException("Datei gelöscht", permanent = true) }
        queue.put(item(1))
        engine.pump()
        until { queue.status(1) == DownloadStatus.FAILED }
        assertEquals(0, queue.rows.getValue(1).attempts)
        assertEquals("Datei gelöscht", queue.rows.getValue(1).errorMessage)
    }

    @Test
    fun wartezeitDesHostersZaehltKeinenVersuch() = runBlocking {
        resolver.resolve = { throw WaitException(901, "Sperre") }
        queue.put(item(1))
        engine.pump()
        until { resolver.waits.isNotEmpty() }
        assertEquals(listOf(1L to 901), resolver.waits.toList())
        assertEquals(0, queue.rows.getValue(1).attempts)
    }

    @Test
    fun nurWlanStartetImMobilnetzNichts() = runBlocking {
        wifiOnly = true
        metered = true
        resolver.resolve = { error("must not start") }
        queue.put(item(1))
        engine.pump()
        delay(100)
        assertEquals(DownloadStatus.QUEUED, queue.status(1))
        assertTrue(engine.isIdle().not())
    }

    @Test
    fun ladenUndFortsetzenUeberHttp() = runBlocking {
        val data = ByteArray(300 * 1024) { (it % 251).toByte() }
        MockWebServer().use { server ->
            // First attempt breaks off after 100 KiB of the part file: the rest comes with Range
            val done = 100 * 1024
            File(dir, "1-file1.bin.part").writeBytes(data.copyOf(done))
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(data, done, data.size - done))
            )
            server.start()
            resolver.resolve = { ResolvedLink(server.url("/file1.bin").toString()) }
            queue.put(item(1, size = data.size.toLong()))
            engine.pump()
            until { queue.status(1) == DownloadStatus.COMPLETED }
            assertEquals("bytes=$done-", server.takeRequest().getHeader("Range"))
            assertArrayEquals(data, completion.completed.single().second)
            assertEquals(data.size.toLong(), queue.rows.getValue(1).downloadedBytes)
        }
    }
}
