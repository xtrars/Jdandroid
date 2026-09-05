package com.jdandroid.data

import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.FileNames
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Online check in the link grabber: asks the hoster whether the file exists
 * and fills in name and size. Runs in the app scope so it completes without
 * an open screen (Click'n'Load, DLC).
 */
object LinkChecker {

    private val permits = Semaphore(3)

    fun schedule(app: JdApp, ids: List<Long>) {
        if (ids.isEmpty()) return
        val dao = app.db.downloadDao()
        val accountDao = app.db.accountDao()
        ids.forEach { id ->
            app.appScope.launch {
                permits.withPermit {
                    val item = dao.byId(id) ?: return@withPermit
                    if (item.status != DownloadStatus.COLLECTED) return@withPermit
                    dao.setOnline(id, OnlineState.CHECKING)
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
            }
        }
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
