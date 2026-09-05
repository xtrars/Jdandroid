package com.jdandroid.data

import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.FileNames
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Online check in the link grabber: asks the hoster whether the file exists
 * and fills in name and size. Runs in the app scope so it completes without
 * an open screen (Click'n'Load, DLC).
 */
object LinkChecker {

    private const val WORKERS = 3

    /** Below SQLite's default limit of 999 bound variables. */
    private const val MARK_CHUNK = 500

    /** Global cap so overlapping schedule() calls never exceed [WORKERS] hoster requests. */
    private val permits = Semaphore(WORKERS)

    fun schedule(app: JdApp, ids: List<Long>) {
        if (ids.isEmpty()) return
        val dao = app.db.downloadDao()
        val accountDao = app.db.accountDao()
        app.appScope.launch {
            runChecks(ids, { dao.setCheckingAll(it) }) { id ->
                permits.withPermit { checkOne(app, dao, accountDao, id) }
            }
        }
    }

    /**
     * Marks all ids CHECKING in a few statements, then drains them with a
     * fixed number of workers instead of one coroutine per link.
     */
    internal suspend fun runChecks(
        ids: List<Long>,
        markChecking: suspend (List<Long>) -> Unit,
        check: suspend (Long) -> Unit
    ) = coroutineScope {
        ids.chunked(MARK_CHUNK).forEach { markChecking(it) }
        val queue = Channel<Long>(Channel.UNLIMITED).apply {
            ids.forEach { trySend(it) }
            close()
        }
        repeat(WORKERS) {
            launch { for (id in queue) check(id) }
        }
    }

    private suspend fun checkOne(app: JdApp, dao: DownloadDao, accountDao: AccountDao, id: Long) {
        val item = dao.byId(id) ?: return
        if (item.status != DownloadStatus.COLLECTED) return
        val hoster = HosterRegistry.byId(item.hosterId)
        val result = try {
            hoster?.checkLink(item.url, accountDao.validForHoster(item.hosterId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        when (result?.online) {
            true -> {
                val name = result.fileName?.let { FileNames.sanitize(it) }
                dao.applyCheck(id, OnlineState.ONLINE, null, name, ArchiveNames.archiveKey(name), result.fileSize)
            }
            false -> dao.applyCheck(id, OnlineState.OFFLINE, result.note ?: app.getString(R.string.service_link_offline), null, null, -1)
            null -> dao.applyCheck(id, OnlineState.UNKNOWN, result?.note ?: app.getString(R.string.service_link_check_unavailable), null, null, -1)
        }
        if (result?.online == true) PackageNaming.refineAutoName(app.db, item.packageId)
    }

    fun recheckAll(app: JdApp) {
        app.appScope.launch {
            val items = app.db.downloadDao().collectedWithOnline(
                listOf(OnlineState.UNKNOWN, OnlineState.OFFLINE, OnlineState.ONLINE, OnlineState.CHECKING)
            )
            schedule(app, items.map { it.id })
        }
    }

}
