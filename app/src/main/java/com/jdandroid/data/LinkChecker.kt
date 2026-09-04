package com.jdandroid.data

import com.jdandroid.JdApp
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
                    val result = runCatching {
                        hoster?.checkLink(item.url, accountDao.validForHoster(item.hosterId))
                    }.getOrNull()
                    when (result?.online) {
                        true -> dao.applyCheck(id, OnlineState.ONLINE, null, result.fileName?.let { sanitize(it) }, result.fileSize)
                        false -> dao.applyCheck(id, OnlineState.OFFLINE, result.note ?: "Datei offline", null, -1)
                        null -> dao.applyCheck(id, OnlineState.UNKNOWN, result?.note ?: "Prüfung nicht möglich", null, -1)
                    }
                    if (result?.online == true) refinePackageName(app, item.packageId)
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

    private suspend fun refinePackageName(app: JdApp, packageId: Long?) {
        val id = packageId ?: return
        val names = app.db.downloadDao().byPackage(id).mapNotNull { it.fileName }
        val name = PackageNaming.commonName(names) ?: return
        app.db.packageDao().refineAutoName(id, name)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().trimStart('.').ifBlank { "download.bin" }
}
