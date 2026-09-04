package com.jdandroid.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jdandroid.JdApp
import com.jdandroid.container.ContainerDecrypter
import com.jdandroid.container.ContainerFiles
import com.jdandroid.data.Account
import com.jdandroid.data.AccountRefresher
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadPackage
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.LinkChecker
import com.jdandroid.data.LinkSink
import com.jdandroid.data.Secrets
import com.jdandroid.engine.DownloadService
import com.jdandroid.engine.Extractor
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ein Paket mit seinen Downloads. */
data class DownloadGroup(
    val pkg: DownloadPackage,
    val items: List<DownloadItem>
) {
    val total: Long get() = items.sumOf { it.fileSize.coerceAtLeast(0) }
    // Nur Eintraege mit bekannter Groesse zaehlen, sonst steht "1,2 GiB / 700 MiB"
    val done: Long get() = items.sumOf { if (it.fileSize > 0) it.downloadedBytes else 0L }
    val speed: Long get() = items.sumOf { it.speedBps }
    val finished: Int get() = items.count { it.status == DownloadStatus.COMPLETED }
    val failed: Int get() = items.count { it.status == DownloadStatus.FAILED }
    val active: Boolean get() = items.any {
        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.EXTRACTING
    }
}

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val jdApp = app as JdApp
    private val dao = jdApp.db.downloadDao()

    private val packageDao = jdApp.db.packageDao()

    private fun grouped(items: List<DownloadItem>, packages: List<DownloadPackage>): List<DownloadGroup> {
        val byPackage = items.groupBy { it.packageId }
        val known = packages.mapNotNull { pkg ->
            byPackage[pkg.id]?.let { DownloadGroup(pkg, it.sortedBy { i -> i.addedAt }) }
        }
        // Eintraege ohne Paket (aus aelteren Versionen) sammeln
        val loose = byPackage[null].orEmpty()
        return if (loose.isEmpty()) known
        else known + DownloadGroup(
            DownloadPackage(id = 0, name = "Ohne Paket", autoNamed = false),
            loose
        )
    }

    /** Downloads (ohne Linksammler-Eintraege) nach Paketen gruppiert - wie im JDownloader. */
    val groups: StateFlow<List<DownloadGroup>> =
        combine(dao.observeAll(), packageDao.observeAll()) { items, packages ->
            grouped(items.filter { it.status != DownloadStatus.COLLECTED }, packages)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Linksammler: noch nicht gestartete Links nach Paketen. */
    val collectorGroups: StateFlow<List<DownloadGroup>> =
        combine(dao.observeAll(), packageDao.observeAll()) { items, packages ->
            grouped(items.filter { it.status == DownloadStatus.COLLECTED }, packages)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startCollectedPackage(packageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.startCollected(packageId)
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    fun startAllCollected() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.startAllCollected()
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    fun recheckCollected() = LinkChecker.recheckAll(jdApp)

    fun removeOfflineCollected() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteOfflineCollected()
            packageDao.deleteEmpty()
        }
    }

    fun renamePackage(packageId: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) { packageDao.rename(packageId, name) }
    }

    fun deletePackage(packageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.byPackage(packageId).forEach { item ->
                if (item.status == DownloadStatus.COLLECTED) dao.delete(item.id)
                else DownloadService.send(getApplication(), DownloadService.ACTION_DELETE, item.id)
            }
            packageDao.delete(packageId)
        }
    }

    fun startPackage(packageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.byPackage(packageId)
                .filter { it.status != DownloadStatus.COMPLETED }
                .forEach { dao.requeue(it.id) }
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    fun pausePackage(packageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.byPackage(packageId).forEach { item ->
                DownloadService.send(getApplication(), DownloadService.ACTION_PAUSE, item.id)
            }
        }
    }

    /** Fuegt alle unterstuetzten Links aus dem Text hinzu (Duplikate werden uebersprungen). */
    fun addLinks(
        text: String,
        packageName: String? = null,
        onDone: (added: Int, toCollector: Boolean) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val toCollector = !jdApp.settings.currentAutoStartLinks()
            val added = LinkSink.addFromText(getApplication(), text, packageName)
            launch(Dispatchers.Main) { onDone(added, toCollector) }
        }
    }

    /**
     * Importiert eine DLC-Container-Datei: entschluesselt sie und reiht die
     * enthaltenen Links ein. Meldet Ergebnis/Fehler ueber [onResult].
     */
    fun importDlc(content: String) {
        AppMessages.progress("DLC wird entschlüsselt …")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Paketstruktur des DLC uebernehmen - wie im JDownloader
                val packages = ContainerDecrypter.decryptDlcPackages(content)
                var added = 0
                packages.forEach { pkg ->
                    added += LinkSink.addUrls(getApplication(), pkg.urls, pkg.name)
                }
                val total = packages.sumOf { it.urls.size }
                val target = if (jdApp.settings.currentAutoStartLinks()) "gestartet"
                else "in den Linksammler übernommen"
                if (added > 0) {
                    AppMessages.success("$added Link(s) in ${packages.size} Paket(en) $target")
                } else {
                    AppMessages.info(
                        "DLC gelesen, aber keine neuen unterstützten Links ($total Link(s) insgesamt)"
                    )
                }
            } catch (e: ContainerDecrypter.ContainerException) {
                AppMessages.error(e.message ?: "DLC-Import fehlgeschlagen")
            } catch (e: Exception) {
                AppMessages.error("DLC-Import fehlgeschlagen: Datei ist kein gültiger Container")
            }
        }
    }

    /**
     * DLC aus dem Dateiwaehler: Lesen und Pruefen laufen im ViewModel-Scope,
     * damit ein Tabwechsel oder Drehen waehrend des Lesens den Import nicht
     * abbricht (ein rememberCoroutineScope stirbt mit dem Bildschirm).
     */
    fun importDlcFromUri(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { ContainerFiles.readText(resolver, uri) }
            val content = result.getOrNull()
            when {
                content == null -> AppMessages.error(
                    result.exceptionOrNull()?.message ?: "DLC-Datei konnte nicht gelesen werden"
                )
                !ContainerFiles.looksLikeDlc(content) ->
                    AppMessages.error("Die gewählte Datei ist kein DLC-Container")
                else -> importDlc(content)
            }
        }
    }

    fun pause(id: Long) = DownloadService.send(getApplication(), DownloadService.ACTION_PAUSE, id)

    fun resume(item: DownloadItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.requeue(item.id)
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    fun retry(item: DownloadItem) = resume(item)

    /** Fertigen Download erneut laden (z.B. Datei versehentlich geloescht). */
    fun redownload(item: DownloadItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.requeueCompleted(item.id)
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    /** Nachtraeglich entpacken (Aktionsmenue). */
    fun extract(id: Long) = DownloadService.send(getApplication(), DownloadService.ACTION_EXTRACT, id)

    /** Alle fertigen Archive eines Pakets entpacken (nur erste Teile eines Sets). */
    fun extractPackage(packageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = dao.byPackage(packageId).filter { it.status == DownloadStatus.COMPLETED }
            val archives = items.filter { item ->
                val name = item.fileName ?: return@filter false
                Extractor.archiveBase(name) != null && !Extractor.isSecondaryVolume(name)
            }
            if (archives.isEmpty()) {
                AppMessages.info("Keine fertigen Archive in diesem Paket")
                return@launch
            }
            archives.forEach { extract(it.id) }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = dao.byId(id) ?: return@launch
            // Linksammler-Eintraege haben nichts auf der Platte: direkt loeschen,
            // ohne den Download-Dienst zu starten
            if (item.status == DownloadStatus.COLLECTED) {
                dao.delete(id)
                packageDao.deleteEmpty()
            } else {
                DownloadService.send(getApplication(), DownloadService.ACTION_DELETE, id)
            }
        }
    }

    fun pauseAll() = DownloadService.send(getApplication(), DownloadService.ACTION_PAUSE_ALL)

    fun resumeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            // Direkt aus der DB: ein StateFlow ohne Sammler haette keinen Wert
            dao.all()
                .filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
                .forEach { dao.requeue(it.id) }
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch(Dispatchers.IO) { dao.clearCompleted() }
    }
}

class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val jdApp = app as JdApp
    private val dao = jdApp.db.accountDao()

    val accounts: StateFlow<List<Account>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hosters: List<Hoster> = HosterRegistry.hosters

    /** Angeforderter Browser-Login (Hoster mit CAPTCHA), null = keiner offen. */
    private val _webLogin = MutableStateFlow<Hoster?>(null)
    val webLogin: StateFlow<Hoster?> = _webLogin

    /**
     * Zustand des Browser-Logins lebt im ViewModel: die WebView wird beim
     * Drehen neu erzeugt, die bereits erkannte Session und die Statuszeile
     * duerfen dabei nicht verloren gehen.
     */
    private val _webLoginStatus = MutableStateFlow(WEB_LOGIN_INITIAL_STATUS)
    val webLoginStatus: StateFlow<String> = _webLoginStatus

    private val _webLoginCookies = MutableStateFlow<String?>(null)
    val webLoginCookies: StateFlow<String?> = _webLoginCookies

    fun setWebLoginStatus(status: String) { _webLoginStatus.value = status }

    fun setWebLoginCookies(cookies: String?) { _webLoginCookies.value = cookies }

    /** Meldung fuer den Nutzer (z.B. Keystore-Fehler beim Speichern), null = keine. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() { _message.value = null }

    fun requestWebLogin(hoster: Hoster) {
        _webLoginStatus.value = WEB_LOGIN_INITIAL_STATUS
        _webLoginCookies.value = null
        _webLogin.value = hoster
    }

    fun cancelWebLogin() {
        _webLogin.value = null
        _webLoginCookies.value = null
    }

    fun completeWebLogin(cookies: String) {
        val hoster = _webLogin.value ?: return
        _webLogin.value = null
        _webLoginCookies.value = null
        addAccountWithCookies(hoster, cookies)
    }

    fun addAccount(hoster: Hoster, username: String?, password: String?, apiKey: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            // Ohne funktionierenden Keystore wird NICHT gespeichert (kein
            // stiller Klartext-Fallback) - der Nutzer bekommt eine Meldung.
            val account = try {
                Account(
                    hosterId = hoster.id,
                    username = username?.ifBlank { null },
                    password = Secrets.encrypt(password?.ifBlank { null }),
                    apiKey = Secrets.encrypt(apiKey?.ifBlank { null })
                )
            } catch (e: Secrets.SecretsException) {
                _message.value = e.message
                return@launch
            }
            check(dao.insert(account))
        }
    }

    /** Konto aus einer im Browser uebernommenen Session anlegen. */
    fun addAccountWithCookies(hoster: Hoster, cookies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = try {
                Account(
                    hosterId = hoster.id,
                    username = "Browser-Login",
                    cookies = Secrets.encrypt(cookies)
                )
            } catch (e: Secrets.SecretsException) {
                _message.value = e.message
                return@launch
            }
            check(dao.insert(account))
        }
    }

    fun check(accountId: Long) {
        viewModelScope.launch(Dispatchers.IO) { AccountRefresher.check(jdApp, accountId) }
    }

    /** Beim Oeffnen der Kontenansicht: veraltete Angaben (Traffic!) nachladen. */
    fun refreshStale() = AccountRefresher.refreshStale(jdApp)

    /** Minutentakt, solange die Kontenansicht sichtbar ist. */
    fun refreshAll() = AccountRefresher.refreshAll(jdApp)

    fun delete(account: Account) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(account) }
    }

    companion object {
        const val WEB_LOGIN_INITIAL_STATUS = "Bitte anmelden – inklusive \"Ich bin kein Roboter\"."
    }
}
