package com.jdandroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jdandroid.JdApp
import com.jdandroid.container.ContainerDecrypter
import com.jdandroid.data.Account
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadPackage
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.LinkSink
import com.jdandroid.data.Secrets
import com.jdandroid.engine.DownloadService
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
    val done: Long get() = items.sumOf { it.downloadedBytes }
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

    val downloads: StateFlow<List<DownloadItem>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Downloads nach Paketen gruppiert - wie im JDownloader. */
    val groups: StateFlow<List<DownloadGroup>> =
        combine(dao.observeAll(), packageDao.observeAll()) { items, packages ->
            val byPackage = items.groupBy { it.packageId }
            val known = packages.mapNotNull { pkg ->
                byPackage[pkg.id]?.let { DownloadGroup(pkg, it.sortedBy { i -> i.addedAt }) }
            }
            // Eintraege ohne Paket (aus aelteren Versionen) sammeln
            val loose = byPackage[null].orEmpty()
            if (loose.isEmpty()) known
            else known + DownloadGroup(
                DownloadPackage(id = 0, name = "Ohne Paket", autoNamed = false),
                loose
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun renamePackage(packageId: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) { packageDao.rename(packageId, name) }
    }

    fun deletePackage(packageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.byPackage(packageId).forEach { item ->
                DownloadService.send(
                    getApplication(), DownloadService.ACTION_DELETE, item.id
                )
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
    fun addLinks(text: String, packageName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            LinkSink.addFromText(getApplication(), text, packageName)
        }
    }

    /**
     * Importiert eine DLC-Container-Datei: entschluesselt sie und reiht die
     * enthaltenen Links ein. Meldet Ergebnis/Fehler ueber [onResult].
     */
    fun importDlc(content: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                // Paketstruktur des DLC uebernehmen - wie im JDownloader
                val packages = ContainerDecrypter.decryptDlcPackages(content)
                var added = 0
                packages.forEach { pkg ->
                    added += LinkSink.addUrls(getApplication(), pkg.urls, pkg.name)
                }
                val total = packages.sumOf { it.urls.size }
                if (added > 0) "$added Link(s) in ${packages.size} Paket(en) aus DLC hinzugefügt"
                else "DLC gelesen, aber keine unterstützten Hoster enthalten " +
                    "($total Link(s) insgesamt)"
            } catch (e: Exception) {
                e.message ?: "DLC-Import fehlgeschlagen"
            }
            launch(Dispatchers.Main) { onResult(message) }
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

    fun delete(id: Long) = DownloadService.send(getApplication(), DownloadService.ACTION_DELETE, id)

    fun pauseAll() = DownloadService.send(getApplication(), DownloadService.ACTION_PAUSE_ALL)

    fun resumeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            downloads.value
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

    private val dao = (app as JdApp).db.accountDao()

    val accounts: StateFlow<List<Account>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hosters: List<Hoster> = HosterRegistry.hosters

    /** Angeforderter Browser-Login (Hoster mit CAPTCHA), null = keiner offen. */
    private val _webLogin = MutableStateFlow<Hoster?>(null)
    val webLogin: StateFlow<Hoster?> = _webLogin

    fun requestWebLogin(hoster: Hoster) { _webLogin.value = hoster }

    fun cancelWebLogin() { _webLogin.value = null }

    fun completeWebLogin(cookies: String) {
        val hoster = _webLogin.value ?: return
        _webLogin.value = null
        addAccountWithCookies(hoster, cookies)
    }

    fun addAccount(hoster: Hoster, username: String?, password: String?, apiKey: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = dao.insert(
                Account(
                    hosterId = hoster.id,
                    username = username?.ifBlank { null },
                    password = Secrets.encrypt(password?.ifBlank { null }),
                    apiKey = Secrets.encrypt(apiKey?.ifBlank { null })
                )
            )
            check(id)
        }
    }

    /** Konto aus einer im Browser uebernommenen Session anlegen. */
    fun addAccountWithCookies(hoster: Hoster, cookies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = dao.insert(
                Account(
                    hosterId = hoster.id,
                    username = "Browser-Login",
                    cookies = Secrets.encrypt(cookies)
                )
            )
            check(id)
        }
    }

    fun check(accountId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = dao.byId(accountId) ?: return@launch
            val hoster = HosterRegistry.byId(account.hosterId) ?: return@launch
            val updated = try {
                val info = hoster.checkAccount(account)
                account.copy(
                    valid = info.valid,
                    premiumUntil = info.premiumUntil,
                    trafficLeft = info.trafficLeft,
                    statusText = info.statusText,
                    lastChecked = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                account.copy(
                    valid = false,
                    statusText = e.message ?: "Prüfung fehlgeschlagen",
                    lastChecked = System.currentTimeMillis()
                )
            }
            dao.update(updated)
        }
    }

    fun delete(account: Account) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(account) }
    }
}
