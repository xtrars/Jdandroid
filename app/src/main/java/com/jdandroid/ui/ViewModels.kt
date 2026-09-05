package com.jdandroid.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
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

/** A package with its downloads and per-item live extraction progress. */
@Immutable
data class DownloadGroup(
    val pkg: DownloadPackage,
    val items: List<DownloadItem>,
    /** Extraction percent per item id, live from the [ProgressBus]; absent = unknown. */
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
    val extracting: Boolean
    /** Extraction percent of the package, -1 = unknown. */
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

    /** Extraction percent of one item, -1 = unknown. */
    fun extractPercent(item: DownloadItem): Int = extractPercents[item.id] ?: -1
}

/**
 * Groups items by package. Items without a package or with a missing package
 * go under [looseName], otherwise they would be invisible. Extraction
 * progress is not stored in the database and comes from [live].
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
 * Overlays [ProgressBus] values onto database items. Items without live
 * values keep their instance; -1 in a live value means "keep the database
 * value" (e.g. bytes while extracting).
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

/** An item whose captcha is being solved in the browser. */
data class CaptchaRequest(val id: Long, val page: CaptchaPage, val hoster: Hoster)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val jdApp = app as JdApp
    private val dao = jdApp.db.downloadDao()

    private val packageDao = jdApp.db.packageDao()

    private val _captcha = MutableStateFlow<CaptchaRequest?>(null)
    val captcha: StateFlow<CaptchaRequest?> = _captcha

    /** Opens the page remembered by the engine, or the file page after a process restart. */
    fun solveCaptcha(item: DownloadItem) {
        val hoster = HosterRegistry.byId(item.hosterId) ?: return
        _captcha.value = CaptchaRequest(item.id, FreeDownloads.captchaPage(item.id) ?: CaptchaPage(item.url), hoster)
    }

    fun cancelCaptcha() { _captcha.value = null }

    /** Stores the direct link from the browser and restarts the item at once. */
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
     * Downloads (without collector items) grouped by package. Bytes, speed and
     * extraction percent come live from the [ProgressBus], not the database.
     */
    val groups: StateFlow<List<DownloadGroup>> =
        combine(dao.observeAll(), packageDao.observeAll(), ProgressBus.state) { items, packages, live ->
            val visible = items.filter { it.status != DownloadStatus.COLLECTED }
            groupDownloads(overlayProgress(visible, live), packages, noPackageName(), live)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Collector: not yet started links grouped by package. */
    val collectorGroups: StateFlow<List<DownloadGroup>> =
        combine(dao.observeAll(), packageDao.observeAll()) { items, packages ->
            groupDownloads(items.filter { it.status == DownloadStatus.COLLECTED }, packages, noPackageName())
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            // Collector items have nothing on disk; no need to start the service.
            dao.deleteCollectedInPackage(packageId)
            if (dao.countByPackageNotCollected(packageId) > 0) {
                // The service deletes items (jobs, files) and then the package row,
                // so no orphans remain.
                DownloadService.send(getApplication(), DownloadService.ACTION_DELETE_PACKAGE, packageId)
            } else {
                packageDao.delete(packageId)
            }
        }
    }

    fun startPackage(packageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.requeuePackage(packageId)
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    fun pausePackage(packageId: Long) =
        DownloadService.send(getApplication(), DownloadService.ACTION_PAUSE_PACKAGE, packageId)

    /** Adds all supported links from the text; duplicates are skipped. */
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

    /** Decrypts a DLC container and queues its links, keeping its package structure. */
    fun importDlc(content: String) {
        val app = getApplication<Application>()
        val res = app.resources
        AppMessages.progress(app.getString(R.string.accounts_dlc_decrypting))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val packages = ContainerDecrypter.decryptDlcPackages(content)
                var added = 0
                packages.forEach { pkg ->
                    added += LinkSink.addUrls(app, pkg.urls, pkg.name)
                }
                val total = packages.sumOf { it.urls.size }
                if (added > 0) {
                    // Quantities as plurals, the sentence as a format string.
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
     * Reads a DLC from the file picker in the ViewModel scope, so a tab
     * switch or rotation while reading does not cancel the import.
     */
    fun importDlcFromUri(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            readDlc(resolver, uri, R.string.accounts_not_dlc_chosen)?.let { importDlc(it) }
        }
    }

    private val _pendingDlc = MutableStateFlow<String?>(null)
    /** DLC content from "Open with"; the main screen imports it and switches tabs. */
    val pendingDlc: StateFlow<String?> = _pendingDlc

    /** Reads a DLC handed to the activity (VIEW/SEND) and publishes it as [pendingDlc]. */
    fun openDlc(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            readDlc(resolver, uri, R.string.accounts_not_dlc_opened)?.let { _pendingDlc.value = it }
        }
    }

    fun consumePendingDlc() {
        _pendingDlc.value = null
    }

    /** Reads and validates a DLC file; reports the failure and returns null otherwise. */
    private fun readDlc(resolver: ContentResolver, uri: Uri, notDlcRes: Int): String? {
        val result = runCatching { ContainerFiles.readText(resolver, uri) }
        val content = result.getOrNull()
        val app = getApplication<Application>()
        return when {
            content == null -> {
                AppMessages.error(
                    result.exceptionOrNull()?.message ?: app.getString(R.string.accounts_dlc_read_failed)
                )
                null
            }
            !ContainerFiles.looksLikeDlc(content) -> {
                AppMessages.error(app.getString(notDlcRes))
                null
            }
            else -> content
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

    /** Downloads a completed item again (e.g. file deleted by accident). */
    fun redownload(item: DownloadItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.requeueCompleted(item.id)
            DownloadService.send(getApplication(), DownloadService.ACTION_PUMP)
        }
    }

    fun extract(id: Long) = DownloadService.send(getApplication(), DownloadService.ACTION_EXTRACT, id)

    /** Extracts all completed archives of a package (first parts of a set only). */
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
            // Collector items have nothing on disk; no need to start the service.
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

    /** Hoster whose browser login is open, null = none. */
    private val _webLogin = MutableStateFlow<Hoster?>(null)
    val webLogin: StateFlow<Hoster?> = _webLogin

    // Browser login state lives here because the WebView is recreated on rotation.
    private val _webLoginStatus = MutableStateFlow(webLoginInitialStatus())
    val webLoginStatus: StateFlow<String> = _webLoginStatus

    private val _webLoginCookies = MutableStateFlow<String?>(null)
    val webLoginCookies: StateFlow<String?> = _webLoginCookies

    fun setWebLoginStatus(status: String) { _webLoginStatus.value = status }

    fun setWebLoginCookies(cookies: String?) { _webLoginCookies.value = cookies }

    /** Message for the user (e.g. a Keystore error while saving), null = none. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() { _message.value = null }

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
            // Without a working Keystore nothing is saved: no silent plaintext fallback.
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

    /** Creates an account from a session accepted in the browser. */
    fun addAccountWithCookies(hoster: Hoster, cookies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = try {
                // No username: the account list labels it "browser login" via the cookies.
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

    /** Reloads stale account data (traffic) when the accounts tab opens. */
    fun refreshStale() = AccountRefresher.refreshStale(jdApp)

    /** Called every minute while the accounts tab is visible. */
    fun refreshAll() = AccountRefresher.refreshAll(jdApp)

    fun delete(account: Account) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(account) }
    }
}

/** State of the settings tab that has to outlive the composition. */
class SettingsViewModel : ViewModel() {
    internal val nfsProbe = NfsProbeRunner(viewModelScope)
}
