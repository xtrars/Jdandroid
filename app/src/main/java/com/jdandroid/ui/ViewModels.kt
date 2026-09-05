package com.jdandroid.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.container.ContainerDecrypter
import com.jdandroid.container.ContainerFiles
import com.jdandroid.core.AppMessages
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.LiveProgress
import com.jdandroid.core.ProgressBus
import com.jdandroid.data.Account
import com.jdandroid.data.AccountRefresher
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadPackage
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.LinkChecker
import com.jdandroid.data.LinkSink
import com.jdandroid.data.Secrets
import com.jdandroid.engine.DownloadService
import com.jdandroid.engine.CaptchaPage
import com.jdandroid.engine.FreeDownloads
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ein Paket mit seinen Downloads. */
@Immutable
data class DownloadGroup(
    val pkg: DownloadPackage,
    val items: List<DownloadItem>,
    /** Entpack-Stand je Eintrag in Prozent (nur live aus dem [ProgressBus]), fehlend = unbekannt. */
    val extractPercents: Map<Long, Int> = emptyMap()
) {
    /** Sum of the known file sizes. */
    val total: Long
    /** Downloaded bytes of entries with a known size only, so "1.2 GiB / 700 MiB" cannot appear. */
    val done: Long
    val speed: Long
    val finished: Int
    val failed: Int
    val active: Boolean
    /** Laeuft gerade ein Entpacken in diesem Paket? */
    val extracting: Boolean
    /** Entpack-Fortschritt in Prozent, -1 = unbekannt. */
    val extractPercent: Int

    // Computed once per instance: the header reads these fields on every recomposition.
    init {
        var total = 0L
        var done = 0L
        var speed = 0L
        var finished = 0
        var failed = 0
        var active = false
        var extracting = false
        var percent = -1
        for (item in items) {
            if (item.fileSize > 0) {
                total += item.fileSize
                done += item.downloadedBytes
            }
            speed += item.speedBps
            when (item.status) {
                DownloadStatus.COMPLETED -> finished++
                DownloadStatus.FAILED -> failed++
                DownloadStatus.RUNNING -> active = true
                DownloadStatus.EXTRACTING -> {
                    active = true
                    extracting = true
                    percent = maxOf(percent, extractPercents[item.id] ?: -1)
                }
                else -> {}
            }
        }
        this.total = total
        this.done = done
        this.speed = speed
        this.finished = finished
        this.failed = failed
        this.active = active
        this.extracting = extracting
        this.extractPercent = percent
    }

    /** Entpack-Stand eines Eintrags in Prozent, -1 = unbekannt. */
    fun extractPercent(item: DownloadItem): Int = extractPercents[item.id] ?: -1
}

/**
 * Gruppiert Eintraege nach Paketen. Eintraege ohne Paket (aeltere Versionen)
 * oder mit einem nicht mehr vorhandenen Paket landen unter [looseName]
 * (uebersetzt vom Aufrufer) - sonst waeren sie in keiner Gruppe und damit
 * unsichtbar. Der Entpack-Stand steht nicht in der Datenbank und wird aus
 * [live] an die Gruppe gehaengt.
 */
internal fun groupDownloads(
    items: List<DownloadItem>,
    packages: List<DownloadPackage>,
    looseName: String,
    live: Map<Long, LiveProgress> = emptyMap()
): List<DownloadGroup> {
    val byPackage = items.groupBy { it.packageId }
    fun group(pkg: DownloadPackage, members: List<DownloadItem>): DownloadGroup {
        val percents = HashMap<Long, Int>()
        members.forEach { m -> live[m.id]?.extractPercent?.takeIf { it >= 0 }?.let { percents[m.id] = it } }
        return DownloadGroup(pkg, members, percents)
    }
    val known = packages.mapNotNull { pkg ->
        byPackage[pkg.id]?.let { group(pkg, it.sortedBy { i -> i.addedAt }) }
    }
    val packageIds = packages.map { it.id }.toSet()
    val loose = items.filter { it.packageId !in packageIds }
    return if (loose.isEmpty()) known
    else known + group(DownloadPackage(id = 0, name = looseName, autoNamed = false), loose)
}

/**
 * Live-Werte aus dem [ProgressBus] ueber die Datenbank-Eintraege legen.
 * Eintraege ohne Live-Werte bleiben dieselbe Instanz; -1 im Live-Wert
 * bedeutet "Datenbankwert behalten" (z.B. Bytestand waehrend des Entpackens).
 */
internal fun overlayProgress(items: List<DownloadItem>, live: Map<Long, LiveProgress>): List<DownloadItem> {
    if (live.isEmpty()) return items
    return items.map { item ->
        val p = live[item.id] ?: return@map item
        item.copy(
            downloadedBytes = if (p.downloadedBytes >= 0) p.downloadedBytes else item.downloadedBytes,
            speedBps = p.speedBps
        )
    }
}

/** Ein Eintrag, dessen Captcha gerade im Browser geloest wird (Seite samt Hoster-Cookies). */
data class CaptchaRequest(val id: Long, val page: CaptchaPage, val hoster: Hoster)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val jdApp = app as JdApp
    private val dao = jdApp.db.downloadDao()

    private val packageDao = jdApp.db.packageDao()

    /** Offene Captcha-Ansicht (Free-Modus), null = keine. */
    private val _captcha = MutableStateFlow<CaptchaRequest?>(null)
    val captcha: StateFlow<CaptchaRequest?> = _captcha

    /**
     * "Captcha loesen": die von der Engine gemerkte Seite oeffnen; nach einem
     * Prozessneustart ist sie unbekannt, dann die Dateiseite selbst.
     */
    fun solveCaptcha(item: DownloadItem) {
        val hoster = HosterRegistry.byId(item.hosterId) ?: return
        _captcha.value = CaptchaRequest(item.id, FreeDownloads.captchaPage(item.id) ?: CaptchaPage(item.url), hoster)
    }

    fun cancelCaptcha() { _captcha.value = null }

    /** Direktlink aus dem Browser: Hinweise hinterlegen, Eintrag sofort starten. */
    fun completeCaptcha(directUrl: String, cookies: String?) {
        val request = _captcha.value ?: return
        _captcha.value = null
        viewModelScope.launch(Dispatchers.IO) {
            FreeDownloads.putHints(request.id, FreeHints(direktUrlAusBrowser = directUrl, cookies = cookies))
            dao.releaseQueued(request.id)
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    /**
     * Downloads (ohne Linksammler-Eintraege) nach Paketen gruppiert - wie im
     * JDownloader. Bytestand, Geschwindigkeit und Entpack-Prozent kommen live
     * aus dem [ProgressBus], nicht aus der Datenbank; Ueberlagern und
     * Gruppieren laufen abseits des Hauptthreads.
     */
    val groups: StateFlow<List<DownloadGroup>> =
        combine(dao.observeAll(), packageDao.observeAll(), ProgressBus.state) { items, packages, live ->
            val visible = items.filter { it.status != DownloadStatus.COLLECTED }
            groupDownloads(overlayProgress(visible, live), packages, noPackageName(), live)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Linksammler: noch nicht gestartete Links nach Paketen. */
    val collectorGroups: StateFlow<List<DownloadGroup>> =
        combine(dao.observeAll(), packageDao.observeAll()) { items, packages ->
            groupDownloads(items.filter { it.status == DownloadStatus.COLLECTED }, packages, noPackageName())
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Uebersetzte Beschriftung der Sammelgruppe fuer Eintraege ohne Paket. */
    private fun noPackageName(): String = getApplication<Application>().getString(R.string.accounts_no_package)

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
            // Linksammler-Eintraege haben nichts auf der Platte: direkt loeschen,
            // ohne den Download-Dienst zu starten
            val items = dao.byPackage(packageId)
            items.filter { it.status == DownloadStatus.COLLECTED }.forEach { dao.delete(it.id) }
            if (items.any { it.status != DownloadStatus.COLLECTED }) {
                // Ein Aufruf fuer das ganze Paket: der Dienst loescht erst die
                // Eintraege (Jobs, Dateien) und dann die Paketzeile - so bleiben
                // keine verwaisten Eintraege zurueck
                DownloadService.send(getApplication(), DownloadService.ACTION_DELETE_PACKAGE, packageId)
            } else {
                packageDao.delete(packageId)
            }
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

    fun pausePackage(packageId: Long) =
        DownloadService.send(getApplication(), DownloadService.ACTION_PAUSE_PACKAGE, packageId)

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
        val app = getApplication<Application>()
        val res = app.resources
        AppMessages.progress(app.getString(R.string.accounts_dlc_decrypting))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Paketstruktur des DLC uebernehmen - wie im JDownloader
                val packages = ContainerDecrypter.decryptDlcPackages(content)
                var added = 0
                packages.forEach { pkg ->
                    added += LinkSink.addUrls(app, pkg.urls, pkg.name)
                }
                val total = packages.sumOf { it.urls.size }
                if (added > 0) {
                    // "3 Links in 2 Paketen gestartet": Mengen als Plurals,
                    // Satz als Formatstring
                    val links = res.getQuantityString(R.plurals.accounts_dlc_links, added, added)
                    val count = packages.size
                    val pkgs = res.getQuantityString(R.plurals.accounts_dlc_packages, count, count)
                    AppMessages.success(
                        app.getString(
                            if (jdApp.settings.currentAutoStartLinks()) R.string.accounts_dlc_started
                            else R.string.accounts_dlc_collected,
                            links, pkgs
                        )
                    )
                } else {
                    AppMessages.info(res.getQuantityString(R.plurals.accounts_dlc_no_new_links, total, total))
                }
            } catch (e: ContainerDecrypter.ContainerException) {
                AppMessages.error(e.message ?: app.getString(R.string.accounts_dlc_import_failed))
            } catch (e: Exception) {
                AppMessages.error(app.getString(R.string.accounts_dlc_invalid))
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
            val app = getApplication<Application>()
            when {
                content == null -> AppMessages.error(
                    result.exceptionOrNull()?.message ?: app.getString(R.string.accounts_dlc_read_failed)
                )
                !ContainerFiles.looksLikeDlc(content) ->
                    AppMessages.error(app.getString(R.string.accounts_not_dlc_chosen))
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
            val archives = dao.completedArchives(packageId).filter { item ->
                !ArchiveNames.isSecondaryVolume(ArchiveNames.repairName(item.fileName ?: return@filter false))
            }
            if (archives.isEmpty()) {
                AppMessages.info(getApplication<Application>().getString(R.string.accounts_no_finished_archives))
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
            // Direkt in der DB, ein Update fuer alle: ein StateFlow ohne Sammler haette keinen Wert
            dao.requeuePausedAndFailed()
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
    private val _webLoginStatus = MutableStateFlow(webLoginInitialStatus())
    val webLoginStatus: StateFlow<String> = _webLoginStatus

    private val _webLoginCookies = MutableStateFlow<String?>(null)
    val webLoginCookies: StateFlow<String?> = _webLoginCookies

    fun setWebLoginStatus(status: String) { _webLoginStatus.value = status }

    fun setWebLoginCookies(cookies: String?) { _webLoginCookies.value = cookies }

    /** Meldung fuer den Nutzer (z.B. Keystore-Fehler beim Speichern), null = keine. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() { _message.value = null }

    /** Erste Statuszeile des Browser-Logins, in der Geraetesprache. */
    private fun webLoginInitialStatus(): String =
        getApplication<Application>().getString(R.string.accounts_web_login_initial)

    fun requestWebLogin(hoster: Hoster) {
        _webLoginStatus.value = webLoginInitialStatus()
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
                // Kein Benutzername: die Kontenliste zeigt anhand der Cookies
                // "Browser-Login" in der Geraetesprache an
                Account(
                    hosterId = hoster.id,
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
}
