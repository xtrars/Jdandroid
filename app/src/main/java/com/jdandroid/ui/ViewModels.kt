package com.jdandroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jdandroid.JdApp
import com.jdandroid.container.ContainerDecrypter
import com.jdandroid.data.Account
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.LinkSink
import com.jdandroid.engine.DownloadService
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val jdApp = app as JdApp
    private val dao = jdApp.db.downloadDao()

    val downloads: StateFlow<List<DownloadItem>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Fuegt alle unterstuetzten Links aus dem Text hinzu (Duplikate werden uebersprungen). */
    fun addLinks(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            LinkSink.addFromText(getApplication(), text)
        }
    }

    /**
     * Importiert eine DLC-Container-Datei: entschluesselt sie und reiht die
     * enthaltenen Links ein. Meldet Ergebnis/Fehler ueber [onResult].
     */
    fun importDlc(content: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val urls = ContainerDecrypter.decryptDlc(content)
                val added = LinkSink.addUrls(getApplication(), urls)
                if (added > 0) "$added Link(s) aus DLC hinzugefügt"
                else "DLC gelesen, aber keine unterstützten Hoster enthalten " +
                    "(${urls.size} Link(s) insgesamt)"
            } catch (e: Exception) {
                e.message ?: "DLC-Import fehlgeschlagen"
            }
            launch(Dispatchers.Main) { onResult(message) }
        }
    }

    fun pause(id: Long) = DownloadService.send(getApplication(), DownloadService.ACTION_PAUSE, id)

    fun resume(item: DownloadItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setStatus(item.id, DownloadStatus.QUEUED)
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
                .forEach { dao.setStatus(it.id, DownloadStatus.QUEUED) }
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

    fun addAccount(hoster: Hoster, username: String?, password: String?, apiKey: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = dao.insert(
                Account(
                    hosterId = hoster.id,
                    username = username?.ifBlank { null },
                    password = password?.ifBlank { null },
                    apiKey = apiKey?.ifBlank { null }
                )
            )
            check(id)
        }
    }

    /** Konto aus einer im Browser uebernommenen Session anlegen. */
    fun addAccountWithCookies(hoster: Hoster, cookies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = dao.insert(
                Account(hosterId = hoster.id, username = "Browser-Login", cookies = cookies)
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
