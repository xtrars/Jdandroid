package com.jdandroid.data

import com.jdandroid.JdApp
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.FileNames
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Online-Pruefung im Linksammler: fragt beim Hoster nach, ob die Datei
 * existiert, und traegt Name und Groesse ein - wie der JDownloader, bevor
 * ein Download ueberhaupt gestartet wird. Laeuft im App-Scope, damit die
 * Pruefung auch ohne offenen Bildschirm (Click'n'Load, DLC) durchlaeuft.
 */
object LinkChecker {

    /** Nicht mehr als drei Hoster-Anfragen gleichzeitig. */
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
                        false -> dao.applyCheck(id, OnlineState.OFFLINE, result.note ?: "Datei offline", null, null, -1)
                        null -> dao.applyCheck(id, OnlineState.UNKNOWN, result?.note ?: "Prüfung nicht möglich", null, null, -1)
                    }
                    if (result?.online == true) PackageNaming.refineAutoName(app.db, item.packageId)
                }
            }
        }
    }

    /** Alle noch ungeprueften oder nicht pruefbaren Eintraege erneut pruefen. */
    fun recheckAll(app: JdApp) {
        app.appScope.launch {
            val items = app.db.downloadDao().collectedWithOnline(
                listOf(OnlineState.UNKNOWN, OnlineState.OFFLINE, OnlineState.ONLINE, OnlineState.CHECKING)
            )
            schedule(app, items.map { it.id })
        }
    }

}
