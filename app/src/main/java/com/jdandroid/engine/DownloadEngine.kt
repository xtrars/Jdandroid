package com.jdandroid.engine

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.jdandroid.JdApp
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.PackageNaming
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.HosterRegistry
import com.jdandroid.hoster.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Fuehrt die eigentlichen Downloads aus: Link aufloesen, Datei mit
 * Range-Resume herunterladen, Fortschritt in die DB schreiben und
 * fertige Dateien optional in den oeffentlichen Download-Ordner exportieren.
 */
class DownloadEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: () -> Unit
) {
    private val app = context.applicationContext as JdApp
    private val dao = app.db.downloadDao()
    private val accountDao = app.db.accountDao()

    // ConcurrentHashMap: size() wird vom Service-Thread ohne Mutex gelesen
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val mutex = Mutex()

    /** Geglaettete Geschwindigkeit je laufendem Download (Bytes/s). */
    private val speeds = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** Letzte Benachrichtigung des Service, um Aktualisierungen zu drosseln. */
    @Volatile
    private var lastNotify = 0L

    /**
     * Serialisiert den Download-Abschluss: verhindert, dass zwei gleichzeitig
     * fertige Teile desselben Archivs sich gegenseitig als "noch ausstehend"
     * sehen und das Entpacken dadurch ganz ausbleibt.
     */
    private val completionMutex = Mutex()

    /** Entpacken/Export laufen in eigenen Jobs, immer nur einer gleichzeitig. */
    private val extractLimiter = Semaphore(1)
    /** Prozessweit (siehe [ExtractionRegistry]): ueberlebt einen Neustart des Dienstes. */
    private val extracting get() = ExtractionRegistry.count

    /**
     * Startsperre: pump() wartet, bis der Dienst haengen gebliebene Eintraege
     * zurueckgesetzt hat. Vorher konnte ein frueher pump() (Netzwerk-Callback)
     * einen Eintrag starten, den requeueRunning() danach erneut einreihte -
     * derselbe Download lief zweimal.
     */
    private val startGate = CompletableDeferred<Unit>()

    private val limiter = SpeedLimiter()

    init {
        // Geschwindigkeitslimit aus den Einstellungen live uebernehmen
        scope.launch {
            app.settings.speedLimitKbps.collect { limiter.limitBps = it.toLong() * 1024 }
        }
    }

    /** Laufende Downloads plus laufende Entpackvorgaenge. */
    val activeCount: Int get() = jobs.size + extracting.get()

    fun markReady() { startGate.complete(Unit) }

    /** Nichts laeuft und nichts wartet - unter der Sperre, damit pump() nicht dazwischenfunkt. */
    suspend fun isIdle(): Boolean = mutex.withLock {
        jobs.isEmpty() && extracting.get() == 0 && dao.queuedCount() == 0
    }

    /** Summe der aktuellen Geschwindigkeiten, fuer die Benachrichtigung. */
    val totalSpeedBps: Long get() = speeds.values.sum()

    /** Service hoechstens einmal pro Sekunde ueber Fortschritt informieren. */
    private fun notifyProgress() {
        val now = System.currentTimeMillis()
        if (now - lastNotify >= NOTIFY_MS) {
            lastNotify = now
            onStateChanged()
        }
    }

    private fun downloadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
            .apply { mkdirs() }

    /**
     * Vom Nutzer gewaehlter Zielordner (Storage Access Framework), z.B. auf der
     * SD-Karte. null, wenn keiner gewaehlt oder die Berechtigung verloren ist.
     */
    private suspend fun targetTree(): DocumentFile? {
        val uri = app.settings.currentDownloadTreeUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, android.net.Uri.parse(uri)) }
            .getOrNull()?.takeIf { it.canWrite() }
    }

    /** Datei in den SAF-Ordner kopieren; liefert den Anzeigepfad oder null bei Fehler. */
    private fun copyToTree(dir: DocumentFile, file: File, name: String): String? {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        // Vorhandene Datei nicht ueberschreiben, sondern durchnummerieren
        var candidate = name
        var index = 2
        while (dir.findFile(candidate) != null && index < 1000) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            candidate = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            index++
        }
        val target = dir.createFile(mime, candidate) ?: return null
        return try {
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run { target.delete(); return null }
            "${dir.name ?: "Zielordner"}/${target.name ?: candidate}"
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun subDir(root: DocumentFile, path: String): DocumentFile? {
        var current: DocumentFile = root
        path.split('/').filter { it.isNotBlank() }.forEach { part ->
            current = current.findFile(part)?.takeIf { it.isDirectory }
                ?: current.createDirectory(part) ?: return null
        }
        return current
    }

    /**
     * Getaktete Verbindung bei aktiver WLAN-Beschraenkung? Dann wird nicht
     * gestartet; die Eintraege bleiben in der Warteschlange.
     */
    private fun blockedByMeteredNetwork(wifiOnly: Boolean): Boolean {
        if (!wifiOnly) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Startet weitere Downloads, solange Slots frei sind. */
    suspend fun pump() {
        startGate.await()
        if (blockedByMeteredNetwork(app.settings.currentWifiOnly())) {
            onStateChanged()
            return
        }
        val max = app.settings.currentMaxConcurrent()
        mutex.withLock {
            while (jobs.size < max) {
                // Bereits laufende Kennungen ausschliessen: nie derselbe Download doppelt
                val running = jobs.keys.toList() + listOf(-1L)
                val next = dao.nextQueued(System.currentTimeMillis(), running) ?: break
                dao.setStatus(next.id, DownloadStatus.RUNNING)
                jobs[next.id] = scope.launch { run(next.id) }
            }
        }
        onStateChanged()
    }

    /**
     * Netzwechsel: bei "Nur WLAN" und getakteter Verbindung laufende Downloads
     * anhalten und zurueck in die Warteschlange legen - sie starten bei
     * WLAN-Rueckkehr automatisch. Sonst einfach weiter pumpen.
     */
    suspend fun onNetworkChanged() {
        if (blockedByMeteredNetwork(app.settings.currentWifiOnly())) {
            val running = mutex.withLock {
                val copy = jobs.toMap()
                jobs.clear()
                copy
            }
            running.values.forEach { it.cancel() }
            // Unter der Abschluss-Sperre: ein Job im NonCancellable-Abschluss
            // (Datei wird gerade verschoben) darf nicht mehr zurueckgestuft werden
            completionMutex.withLock { running.keys.forEach { dao.requeueIfRunning(it) } }
            onStateChanged()
        } else {
            pump()
        }
    }

    suspend fun pause(id: Long) {
        mutex.withLock { jobs.remove(id) }?.cancel()
        // Nur RUNNING/QUEUED pausieren - ein Eintrag, der gerade fertig wird
        // oder entpackt, bleibt unberuehrt (sonst Neudownload beim Fortsetzen).
        // Unter der Abschluss-Sperre, damit ein laufender Abschluss zu Ende kommt
        completionMutex.withLock { dao.pauseIfActive(id) }
        pump()
    }

    suspend fun cancelAndDelete(id: Long) {
        mutex.withLock { jobs.remove(id) }?.cancel()
        val item = dao.byId(id)
        dao.delete(id)
        item?.let {
            tempFile(it).delete()
            // Archivteile im App-Ordner mit entfernen, wenn kein anderer Eintrag
            // dasselbe Archiv referenziert - sonst bleiben sie unsichtbar liegen
            val base = it.fileName?.let(Extractor::archiveBase)
            if (base != null && dao.all().none { o -> Extractor.archiveBase(o.fileName ?: "") == base }) {
                downloadDir().listFiles()
                    ?.filter { f -> f.isFile && Extractor.archiveBase(f.name) == base }
                    ?.forEach { f -> f.delete() }
            }
        }
        pump()
    }

    /** Alle Eintraege eines Pakets pausieren - ein Dienst-Aufruf statt einem je Eintrag. */
    suspend fun pausePackage(packageId: Long) {
        dao.byPackage(packageId).forEach { pause(it.id) }
    }

    /**
     * Paket samt Eintraegen loeschen: erst jeden Eintrag (Job abbrechen, Dateien
     * entfernen), dann die Paketzeile - so bleiben keine verwaisten Eintraege
     * zurueck, deren Paket schon verschwunden ist.
     */
    suspend fun deletePackage(packageId: Long) {
        dao.byPackage(packageId).forEach { cancelAndDelete(it.id) }
        app.db.packageDao().delete(packageId)
    }

    suspend fun pauseAll() {
        // Erst die Warteschlange anhalten: die abgebrochenen Jobs rufen in ihrem
        // finally pump() auf und wuerden sonst sofort die naechsten Eintraege starten.
        dao.pauseQueued()
        val running = mutex.withLock {
            val copy = jobs.toMap()
            jobs.clear()
            copy
        }
        running.values.forEach { it.cancel() }
        // Entpackende Eintraege bleiben EXTRACTING (laufen unter NonCancellable weiter)
        completionMutex.withLock { running.keys.forEach { dao.pauseIfActive(it) } }
        onStateChanged()
    }

    private fun tempFile(item: DownloadItem): File {
        val name = item.fileName ?: "download-${item.id}"
        return File(downloadDir(), "${item.id}-$name.part")
    }

    private suspend fun run(id: Long) {
        try {
            val item = dao.byId(id) ?: return
            // Liegt das Archiv bereits vollstaendig im App-Ordner (z.B. nach
            // Pause waehrend des Entpackens), nicht erneut laden
            item.fileName?.let { name ->
                val existing = File(downloadDir(), name)
                if (item.fileSize > 0 && existing.isFile && existing.length() == item.fileSize) {
                    completeDownload(id, existing, name)
                    return
                }
            }
            val hoster = HosterRegistry.byId(item.hosterId)
                ?: throw HosterException("Unbekannter Hoster", true)
            val account = accountDao.validForHoster(item.hosterId)
            val resolved = hoster.resolve(item.url, account)

            var current = dao.byId(id) ?: return
            val resolvedName = resolved.fileName?.let { FileNames.sanitize(it) }
            if (resolvedName != null && FileNames.preferName(current.fileName, resolvedName)) {
                current = adoptFileName(current, resolvedName)
                PackageNaming.refineAutoName(app.db, current.packageId)
            }
            download(current, resolved.directUrl, resolved.hash)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HosterException) {
            if (e.permanent) {
                dao.setStatus(id, DownloadStatus.FAILED, e.message)
            } else {
                handleTransientFailure(id, e.message ?: "Fehler")
            }
        } catch (e: com.jdandroid.data.Secrets.SecretsException) {
            // Zugangsdaten nicht entschluesselbar: Wiederholen ist sinnlos
            dao.setStatus(id, DownloadStatus.FAILED, e.message)
        } catch (e: IllegalArgumentException) {
            // Unbrauchbare Download-Adresse (z.B. relativer Link): Wiederholen aendert nichts
            dao.setStatus(id, DownloadStatus.FAILED, "Ungültige Download-Adresse: ${e.message}")
        } catch (e: Exception) {
            // Abbruch (Pause/Loeschen) kommt von OkHttp als IOException("Canceled"):
            // nicht als voruebergehenden Fehler zaehlen, sondern sauber beenden
            coroutineContext.ensureActive()
            // Netzwerkfehler und Abbrueche sind typischerweise voruebergehend
            handleTransientFailure(id, e.message ?: e.javaClass.simpleName)
        } finally {
            // Auch bei Abbruch zuverlaessig austragen: withLock wuerde in einer
            // abgebrochenen Coroutine bei Konkurrenz sofort werfen
            withContext(NonCancellable) {
                speeds.remove(id)
                mutex.withLock { jobs.remove(id) }
            }
            scope.launch { pump() }
        }
    }

    /**
     * Vorübergehende Fehler (Netzwerkabbruch, Serverhänger) werden automatisch
     * wiederholt - mit exponentiell wachsender Wartezeit, damit ein dauerhaft
     * gestörter Hoster nicht dauerfeuert. Erst danach gilt der Download als
     * gescheitert.
     */
    private suspend fun handleTransientFailure(id: Long, message: String) {
        val item = dao.byId(id) ?: return
        val attempts = item.attempts + 1
        if (attempts > MAX_ATTEMPTS) {
            dao.setStatus(
                id, DownloadStatus.FAILED,
                "$message (nach $MAX_ATTEMPTS Versuchen aufgegeben)"
            )
            return
        }
        val backoff = backoffMillis(attempts)
        dao.scheduleRetry(
            id, attempts, System.currentTimeMillis() + backoff,
            "$message – Versuch $attempts/$MAX_ATTEMPTS in ${backoff / 1000}s"
        )
        // Nach Ablauf der Wartezeit erneut anstossen
        scope.launch {
            kotlinx.coroutines.delay(backoff)
            pump()
        }
    }

    private suspend fun download(item: DownloadItem, directUrl: String, expectedHash: String? = null) {
        var target = tempFile(item)
        var offset = if (target.exists()) target.length() else 0L

        val builder = Request.Builder()
            .url(directUrl)
            .header("User-Agent", Http.USER_AGENT)
            // Ohne gzip: sonst fehlt Content-Length und die Vollstaendigkeitspruefung
            .header("Accept-Encoding", "identity")
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        // Bei Pause/Loeschen die Verbindung sofort kappen: sonst wirkt der
        // Abbruch erst nach dem naechsten Socket-Read (bis zu 60 s). Ein
        // invokeOnCompletion-Handler feuert erst im Endzustand des Jobs - also
        // erst, wenn der blockierende Read ohnehin zurueck ist. Der Waechter
        // reagiert dagegen sofort auf den Uebergang nach "cancelling".
        val call = Http.client.newCall(builder.build())
        val vanished = !coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { resp -> transfer(resp, item, target, offset) { target = it } }
            } finally {
                watcher.cancel()
            }
        }
        if (vanished) return

        // Integritaet pruefen, wenn der Hoster eine Pruefsumme geliefert hat
        if (expectedHash != null) verifyHash(target, expectedHash)

        val finalName = dao.byId(item.id)?.fileName ?: target.name.removeSuffix(".part")
        completeDownload(item.id, target, finalName)
        onStateChanged()
    }

    /**
     * Antwort einordnen und die Daten in [initialTarget] schreiben. Liefert
     * false, wenn der Eintrag zwischenzeitlich geloescht wurde. [onTarget]
     * meldet eine umbenannte Teildatei (Name kam erst mit der Antwort).
     */
    private suspend fun transfer(
        resp: okhttp3.Response,
        item: DownloadItem,
        initialTarget: File,
        initialOffset: Long,
        onTarget: (File) -> Unit
    ): Boolean {
        var target = initialTarget
        var offset = initialOffset
        when (
            classifyResponse(
                resp.code, resp.header("Content-Type"), resp.header("Content-Disposition"),
                offset, item.fileSize
            )
        ) {
            // 416: angefragter Bereich hinter Dateiende -> Datei war schon vollstaendig.
            ResponseKind.AlreadyComplete -> {
                dao.updateProgress(item.id, offset, offset, 0)
                return true
            }
            // Teildatei zu lang (z.B. Fremdinhalt): muss neu geladen werden
            ResponseKind.RestartMismatch -> {
                target.delete()
                throw HosterException("Teildatei passt nicht zur Dateigröße – Neustart")
            }
            ResponseKind.HttpError -> throw HosterException("Server antwortete mit HTTP ${resp.code}")
            // HTML statt Datei (abgelaufener Link, Sitzungsseite, Fehlerseite):
            // niemals als Dateiinhalt speichern - sonst landet die Seite in der
            // .part und wird beim Fortsetzen mit dem echten Rest verklebt.
            ResponseKind.HtmlPage -> throw HosterException(
                "Server lieferte eine HTML-Seite statt der Datei (${resp.request.url.host}) – " +
                    "Link wird neu aufgelöst"
            )
            // Server ignoriert Range -> von vorn beginnen
            ResponseKind.RangeIgnored -> {
                target.delete()
                offset = 0
            }
            ResponseKind.Continue -> Unit
        }
        val body = resp.body ?: throw HosterException("Leere Antwort beim Download")
        val total = if (body.contentLength() >= 0) body.contentLength() + offset else item.fileSize

        // Speicherplatz vorab pruefen: sonst bricht der Download erst nach
        // Minuten mit einem nichtssagenden IO-Fehler ab.
        val needed = if (total > 0) total - offset else 0
        val free = downloadDir().usableSpace
        if (needed > 0 && free in 0 until needed) {
            throw HosterException(
                "Zu wenig Speicherplatz: ${needed / (1 shl 20)} MiB benötigt, " +
                    "${free / (1 shl 20)} MiB frei",
                permanent = true
            )
        }

        // Dateiname ggf. aus Content-Disposition oder URL ableiten. Der vom
        // Server gelieferte Name ersetzt einen Platzhalter ohne Endung (z.B.
        // aus dem Seitentitel der Linkpruefung) - sonst erkennt die App das
        // Archiv nicht und entpackt nie.
        var current = dao.byId(item.id) ?: return false
        val serverName = FileNames.fromDisposition(resp.header("Content-Disposition"))
        val candidate = when {
            current.fileName == null -> FileNames.fromResponse(resp.header("Content-Disposition"), resp.request.url)
            serverName != null && FileNames.preferName(current.fileName, serverName) -> serverName
            else -> null
        }
        if (candidate != null) {
            current = adoptFileName(current, candidate)
            target = tempFile(current)
            onTarget(target)
        }

        var written = offset
        val startedAt = System.currentTimeMillis()
        var lastDbWrite = startedAt
        var attemptsReset = false
        // Geschwindigkeit als gleitender Durchschnitt ueber SPEED_WINDOW_MS:
        // der Rohwert einzelner Messintervalle springt stark und liess die
        // Anzeige flackern. In die DB (und damit in die UI) geht der Wert
        // nur alle DB_WRITE_MS.
        val samples = ArrayDeque<Pair<Long, Long>>()
        samples.addLast(startedAt to written)
        body.byteStream().use { input ->
            java.io.FileOutputStream(target, offset > 0).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    limiter.throttle(read)
                    if (!attemptsReset && written - offset >= PROGRESS_RESET_BYTES) {
                        // Echter Fortschritt: ein wackliges Netz darf einen
                        // fortsetzbaren Download nicht nach 5 Abbruechen aufgeben
                        attemptsReset = true
                        dao.resetAttempts(item.id)
                    }
                    val now = System.currentTimeMillis()
                    if (now - samples.last().first >= SAMPLE_MS) {
                        samples.addLast(now to written)
                        while (samples.size > 2 && now - samples.first().first > SPEED_WINDOW_MS) {
                            samples.removeFirst()
                        }
                        val (t0, b0) = samples.first()
                        val speed = if (now > t0) (written - b0) * 1000 / (now - t0) else 0L
                        speeds[item.id] = speed
                        if (now - lastDbWrite >= DB_WRITE_MS) {
                            dao.updateProgress(item.id, written, total, speed)
                            lastDbWrite = now
                            notifyProgress()
                        }
                    }
                    kotlinx.coroutines.yield()
                }
            }
        }
        speeds.remove(item.id)
        dao.updateProgress(item.id, written, total, 0)
        if (total > 0 && written < total) {
            throw IOException("Download unvollständig ($written von $total Bytes)")
        }
        return true
    }

    /**
     * Abschluss eines Downloads: Archive werden (wenn aktiviert) automatisch
     * entpackt, sobald alle Teile vorliegen; alles andere wird direkt exportiert.
     *
     * Nicht abbrechbar: eine Pause waehrend des Exports liess vorher die Kopie
     * zu Ende laufen, den Statuswechsel aber scheitern - Eintrag "pausiert" bei
     * 100 %, Teildatei weg, beim Fortsetzen Neudownload plus Duplikat.
     */
    private suspend fun completeDownload(id: Long, temp: File, originalName: String) = withContext(NonCancellable) {
        var fileName = originalName
        var base = Extractor.archiveBase(fileName)
        if (base == null) {
            // "name part1 rar" (Hoster hat Punkte durch Leerzeichen ersetzt)
            val repaired = Extractor.repairName(fileName)
            if (Extractor.archiveBase(repaired) != null) {
                fileName = repaired
                dao.setFileName(id, fileName)
                base = Extractor.archiveBase(fileName)
            }
        }
        if (base == null) {
            // Name ohne Archiv-Endung, Inhalt aber ein Archiv (falscher oder
            // fehlender Name vom Hoster): Endung anhand der Magic Bytes ergaenzen
            Extractor.sniffExtension(temp)?.let { ext ->
                fileName = "$fileName.$ext"
                dao.setFileName(id, fileName)
                base = Extractor.archiveBase(fileName)
            }
        }
        val autoExtract = app.settings.currentAutoExtract()

        if (!autoExtract || base == null) {
            // Verschieben und Statuswechsel unter der Abschluss-Sperre: pause()/
            // Netzwechsel warten so, statt den Eintrag mit bereits verschobener
            // Teildatei auf PAUSED/QUEUED zu setzen (Neudownload plus Duplikat)
            val packageId = completionMutex.withLock {
                val path = finish(temp, fileName)
                markCompleted(id, path, null)
                dao.byId(id)?.packageId
            }
            // Ein wartendes Archiv-Set desselben Pakets kann jetzt vollstaendig sein
            retryWaitingSets(packageId)
            return@withContext
        }

        val archiveFile = File(downloadDir(), fileName)
        // Entscheidung unter der Sperre: zwei gleichzeitig fertige Teile duerfen
        // sich nicht gegenseitig als "noch ausstehend" sehen.
        val shouldExtract = completionMutex.withLock {
            val all = dao.all()
            val self = all.firstOrNull { it.id == id }
            // Gleichnamiges Archiv eines anderen Pakets liegt bereits flach im
            // App-Ordner: nicht ueberschreiben, sondern als normale Datei ablegen
            val clash = archiveFile.isFile && temp.path != archiveFile.path && all.any { other ->
                other.packageId != self?.packageId && other.fileName == fileName &&
                    other.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.EXTRACTING)
            }
            if (clash) {
                markCompleted(id, finish(temp, fileName), "Gleichnamiges Archiv eines anderen Pakets vorhanden, nicht entpackt")
                return@withLock false
            }
            // Archiv-Volume unter echtem Namen im App-Ordner ablegen, damit
            // Multipart-Teile zueinander finden
            if (temp.path != archiveFile.path) {
                archiveFile.delete()
                temp.renameTo(archiveFile)
            }
            val pending = ArchiveSets.pendingParts(all, id, self?.packageId, base!!)
            if (pending || ExtractionRegistry.isActive(base!!)) {
                markCompleted(id, archiveFile.absolutePath, WAITING_NOTE)
                false
            } else {
                // Alle Teile des Sets zeigen "wird entpackt", nicht nur der zuletzt
                // fertige - sonst wirkt der Zustand willkuerlich verteilt
                dao.setExtractingSet(ArchiveSets.archiveSetIds(all, id, base!!))
                true
            }
        }
        if (!shouldExtract) return@withContext

        startExtraction(id, base!!, archiveFile)
    }

    /**
     * Set ist vollstaendig und bereits EXTRACTING: erstes Volume suchen und
     * entpacken. Fehlt es, alle Teile des Sets zurueck auf fertig - nicht nur
     * den ausloesenden, sonst bleiben die uebrigen dauerhaft EXTRACTING.
     */
    private suspend fun startExtraction(id: Long, base: String, archiveFile: File) {
        val primary = Extractor.findPrimaryVolume(downloadDir(), base)
        if (primary == null) {
            dao.byId(id)?.let { com.jdandroid.data.AccountRefresher.refreshHoster(app, it.hosterId) }
            dao.completeExtractingSet(archiveSetIds(id, base), archiveFile.absolutePath, "Erstes Archiv-Teil fehlt, nicht entpackt")
            return
        }
        // Entpacken in eigenem Job: der Download-Slot wird sofort frei, die
        // Warteschlange steht nicht minutenlang hinter einem grossen RAR.
        launchExtraction(id, base, primary, archiveFile)
    }

    /**
     * Fertige Archiv-Teile mit [WAITING_NOTE] im Paket erneut pruefen: sobald der
     * letzte ausstehende Eintrag des Pakets einen Namen bekommt oder als
     * Nicht-Archiv fertig wird, stoesst das sonst niemand mehr an.
     */
    private suspend fun retryWaitingSets(packageId: Long?) = withContext(NonCancellable) {
        if (packageId == null) return@withContext
        val ready = completionMutex.withLock {
            val all = dao.all()
            all.filter {
                it.packageId == packageId && it.status == DownloadStatus.COMPLETED &&
                    it.errorMessage == WAITING_NOTE
            }.groupBy { Extractor.archiveBase(it.fileName ?: "") }
                .mapNotNull { (base, parts) ->
                    val self = parts.first()
                    if (base == null || ArchiveSets.pendingParts(all, self.id, packageId, base) ||
                        ExtractionRegistry.isActive(base)
                    ) return@mapNotNull null
                    dao.setExtractingSet(ArchiveSets.archiveSetIds(all, self.id, base))
                    Triple(self.id, base, File(downloadDir(), self.fileName!!))
                }
        }
        ready.forEach { (id, base, archiveFile) -> startExtraction(id, base, archiveFile) }
    }

    /** Siehe [ArchiveSets.archiveSetIds]: fertige/entpackende Teile des Sets im selben Paket, inklusive [id]. */
    private suspend fun archiveSetIds(id: Long, base: String): List<Long> =
        ArchiveSets.archiveSetIds(dao.all(), id, base)

    private suspend fun launchExtraction(id: Long, base: String, primary: File, archiveFile: File) {
        val setIds = archiveSetIds(id, base)
        // Laeuft dieses Archiv bereits (z.B. aus einer frueheren Dienst-Instanz),
        // nicht ein zweites Mal entpacken; die laufende Instanz schliesst das Set ab
        if (!ExtractionRegistry.start(base, setIds)) return
        extracting.incrementAndGet()
        onStateChanged()
        scope.launch {
            try {
                extractAndExport(id, base, primary, archiveFile)
            } finally {
                ExtractionRegistry.finish(base, setIds)
                extracting.decrementAndGet()
                onStateChanged()
                scope.launch { pump() }
            }
        }
    }

    /**
     * Nachtraegliches Entpacken eines fertigen Downloads (Aktionsmenue). Alle
     * Teile des Archiv-Sets werden bei Bedarf aus dem Zielordner (SAF oder
     * Downloads/JDAndroid) in den App-Ordner zurueckgeholt. Liefert eine
     * Fehlermeldung oder null, wenn das Entpacken gestartet wurde.
     */
    suspend fun extractNow(id: Long): String? {
        // Als laufender Vorgang zaehlen, bevor Dateien zurueckgeholt werden:
        // sonst haelt sich der frisch gestartete Dienst fuer untaetig, beendet
        // sich, und die naechste Instanz reiht die EXTRACTING-Eintraege neu ein
        startGate.await()
        extracting.incrementAndGet()
        onStateChanged()
        try {
            return extractNowInner(id)
        } finally {
            extracting.decrementAndGet()
            onStateChanged()
        }
    }

    private suspend fun extractNowInner(id: Long): String? {
        val item = dao.byId(id) ?: return "Eintrag nicht gefunden"
        if (item.status == DownloadStatus.EXTRACTING) return "Wird bereits entpackt"
        if (item.status != DownloadStatus.COMPLETED) return "Nur fertige Downloads lassen sich entpacken"
        var name = item.fileName ?: return "Dateiname unbekannt"
        var base = Extractor.archiveBase(name)
        if (base == null) {
            // Namen wie "name part1 rar": alle Teile des Sets umbenennen (Datei
            // im App-Ordner bzw. aus dem Zielordner zurueckgeholt) und in der
            // Datenbank korrigieren
            val repaired = Extractor.repairName(name)
            val repairedBase = Extractor.archiveBase(repaired)
            if (repairedBase != null) {
                val parts = dao.all().filter {
                    it.status == DownloadStatus.COMPLETED &&
                        Extractor.archiveBase(Extractor.repairName(it.fileName ?: "")) == repairedBase
                }
                for (part in parts) {
                    val oldName = part.fileName ?: continue
                    val newName = Extractor.repairName(oldName)
                    val local = File(downloadDir(), newName)
                    if (!local.isFile) {
                        val oldLocal = File(downloadDir(), oldName)
                        if (oldLocal.isFile) oldLocal.renameTo(local) else restoreArchive(part, local)
                    }
                    dao.setFileName(part.id, newName)
                }
                name = repaired
                base = repairedBase
            }
        }
        if (base == null) {
            // Vielleicht ein Archiv ohne passende Endung
            val local = File(downloadDir(), name).takeIf { it.isFile }
                ?: run { val f = File(downloadDir(), name); if (restoreArchive(item, f)) f else null }
            val ext = local?.let { Extractor.sniffExtension(it) } ?: return "Kein Archiv: $name"
            val renamed = File(downloadDir(), "$name.$ext")
            local.renameTo(renamed)
            name = renamed.name
            dao.setFileName(id, name)
            base = Extractor.archiveBase(name) ?: return "Kein Archiv: $name"
        }
        val set = dao.all().filter {
            it.status == DownloadStatus.COMPLETED && Extractor.archiveBase(it.fileName ?: "") == base
        }
        for (part in set) {
            val partName = part.fileName ?: continue
            val local = File(downloadDir(), partName)
            if (!local.isFile && !restoreArchive(part, local)) {
                return "Archivteil nicht mehr vorhanden: $partName"
            }
        }
        val primary = Extractor.findPrimaryVolume(downloadDir(), base)
            ?: return "Erstes Archiv-Teil fehlt"
        if (ExtractionRegistry.isActive(base)) return "Wird bereits entpackt"
        completionMutex.withLock {
            val all = dao.all()
            // Laufende Teile gehoeren nicht ins Set: sie wuerden auf EXTRACTING
            // gesetzt und nach dem Entpacken als "fertig" markiert, obwohl sie noch laden
            if (ArchiveSets.pendingParts(all, id, item.packageId, base, ArchiveSets.LOADING)) {
                return "Archiv unvollständig – weitere Teile werden noch geladen"
            }
            dao.setExtractingSet(ArchiveSets.archiveSetIds(all, id, base))
        }
        launchExtraction(id, base, primary, File(downloadDir(), name))
        return null
    }

    /**
     * Fertige Archivdatei in den App-Ordner zurueckholen: aus dem gemerkten
     * Pfad, dem eigenen Zielordner (SAF) oder Downloads/JDAndroid (MediaStore).
     */
    private suspend fun restoreArchive(item: DownloadItem, dest: File): Boolean {
        val name = item.fileName ?: return false
        item.localPath?.let { path ->
            val f = File(path)
            if (f.isFile && f.path != dest.path) {
                return runCatching { f.copyTo(dest, overwrite = true); true }.getOrDefault(false)
            }
        }
        targetTree()?.findFile(name)?.takeIf { it.isFile }?.let { doc ->
            return copyUriTo(doc.uri, dest)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/JDAndroid%")
            runCatching {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val mediaId = cursor.getLong(0)
                            val uri = android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, mediaId
                            )
                            return copyUriTo(uri, dest)
                        }
                    }
            }
        }
        return false
    }

    private fun copyUriTo(uri: android.net.Uri, dest: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } != null
    } catch (_: Exception) {
        dest.delete()
        false
    }

    /** Entpacken, exportieren, Set aktualisieren - immer nur eines gleichzeitig, nicht abbrechbar. */
    private suspend fun extractAndExport(id: Long, base: String, primary: File, archiveFile: File) =
        withContext(NonCancellable) {
            extractLimiter.withPermit {
                var finished = false
                var failure: String? = null
                try {
                    // Immer in einen Unterordner mit dem Paketnamen (wie im
                    // JDownloader); ohne Paket der Archivname
                    val folder = packageFolder(id) ?: base
                    val extractDir = File(downloadDir(), folder)
                    val setIds = archiveSetIds(id, base)
                    // Fortschritt in Prozent fuer alle Teile, hoechstens jede Sekunde
                    var lastWrite = 0L
                    var lastPercent = -1
                    val listener = Extractor.ProgressListener { done, total ->
                        if (total <= 0) return@ProgressListener
                        val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                        val now = System.currentTimeMillis()
                        if (percent != lastPercent && (now - lastWrite >= 1000 || percent == 100)) {
                            lastWrite = now
                            lastPercent = percent
                            scope.launch { dao.setExtractProgress(setIds, percent) }
                        }
                    }
                    Extractor.extract(
                        primary, extractDir,
                        app.settings.currentPasswords(),
                        app.settings.currentExtractExcludes(),
                        listener
                    )
                    val exportedPath = exportDirectory(extractDir, folder)
                    if (app.settings.currentDeleteArchive()) {
                        downloadDir().listFiles()
                            ?.filter { Extractor.archiveBase(it.name) == base }
                            ?.forEach { it.delete() }
                    }
                    dao.byId(id)?.let { com.jdandroid.data.AccountRefresher.refreshHoster(app, it.hosterId) }
                    // Alle Teile des Sets zurueck auf fertig, mit dem Zielordner
                    dao.completeExtractingSet(setIds, exportedPath, null)
                    finished = true
                    if (app.settings.currentRemoveLinksAfterExtract()) {
                        removeExtractedEntries(id, base)
                    }
                } catch (e: Throwable) {
                    // Auch Error (OutOfMemoryError, UnsatisfiedLinkError des nativen
                    // 7-Zip): sonst bleibt das Set fuer immer EXTRACTING
                    failure = e.message ?: e.javaClass.simpleName
                } finally {
                    if (!finished) {
                        runCatching {
                            dao.completeExtractingSet(archiveSetIds(id, base), archiveFile.absolutePath, failure)
                        }
                    }
                }
            }
        }

    /**
     * Wie im JDownloader ("Links nach dem Entpacken entfernen"): alle fertigen
     * Eintraege dieses Archivs (alle Teile) verschwinden aus der Liste, leere
     * Pakete werden aufgeraeumt. Die entpackten Dateien bleiben natuerlich.
     */
    private suspend fun removeExtractedEntries(id: Long, base: String) {
        val all = dao.all()
        val packageId = all.firstOrNull { it.id == id }?.packageId
        all
            .filter { it.id == id || it.status == DownloadStatus.COMPLETED }
            .filter { it.packageId == packageId && Extractor.archiveBase(it.fileName ?: "") == base }
            .forEach { dao.delete(it.id) }
        app.db.packageDao().deleteEmpty()
    }

    private suspend fun markCompleted(id: Long, path: String?, note: String?) {
        // Traffic-Stand des Hosters nachladen (gedrosselt), damit die
        // Kontenansicht den Verbrauch zeigt
        dao.byId(id)?.let { com.jdandroid.data.AccountRefresher.refreshHoster(app, it.hosterId) }
        // Bedingt: nur wenn der Eintrag noch laeuft/entpackt (nicht zwischenzeitlich
        // pausiert oder geloescht)
        dao.completeIfActive(id, path, note)
    }

    /**
     * Exportiert alle entpackten Dateien in den oeffentlichen Download-Ordner
     * (Downloads/JDAndroid/<base>/...). Ohne Export bleiben sie im App-Ordner.
     */
    /** Ordnername aus dem Paketnamen, dateisystemtauglich; null ohne Paket. */
    private suspend fun packageFolder(id: Long): String? {
        val packageId = dao.byId(id)?.packageId ?: return null
        val name = app.db.packageDao().byId(packageId)?.name ?: return null
        return FileNames.clean(name)?.trimEnd('.')?.let { FileNames.limitLength(it, 120) }?.ifBlank { null }
    }

    private suspend fun exportDirectory(dir: File, base: String): String {
        // Eigener Zielordner (SAF) hat Vorrang vor Downloads/JDAndroid
        targetTree()?.let { root ->
            val target = subDir(root, base) ?: return dir.absolutePath
            var allOk = true
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relDir = file.parentFile!!.relativeTo(dir).path
                val destDir = if (relDir.isEmpty()) target else subDir(target, relDir)
                if (destDir == null || copyToTree(destDir, file, file.name) == null) allOk = false
                else file.delete()
            }
            return if (allOk) {
                dir.deleteRecursively()
                "${root.name ?: "Zielordner"}/$base"
            } else dir.absolutePath
        }
        // Direkter SDK_INT-Vergleich statt Hilfsvariable: Lint (AGP 8.13) erkennt
        // den Versions-Guard sonst nicht und meldet NewApi fuer MediaStore.Downloads.
        if (!app.settings.currentExportToDownloads() || Build.VERSION.SDK_INT < 29) return dir.absolutePath
        val resolver = context.contentResolver
        var allOk = true
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relDir = file.parentFile!!.relativeTo(dir).path
            val relativePath = buildString {
                append(Environment.DIRECTORY_DOWNLOADS).append("/JDAndroid/").append(base)
                if (relDir.isNotEmpty()) append('/').append(relDir)
            }
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    if (!copyToMediaStore(resolver, uri, file)) {
                        allOk = false
                    } else {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        file.delete()
                    }
                } else {
                    allOk = false
                }
            } catch (_: Exception) {
                allOk = false
            }
        }
        return if (allOk) {
            dir.deleteRecursively()
            "Downloads/JDAndroid/$base"
        } else {
            dir.absolutePath
        }
    }

    /**
     * Vergleicht die Pruefsumme der Datei mit der vom Hoster gemeldeten. Bei
     * Abweichung wird die Datei verworfen und der Download (voruebergehender
     * Fehler) automatisch wiederholt.
     */
    private fun verifyHash(file: File, expected: String) {
        val algorithm = when (expected.length) {
            32 -> "MD5"
            40 -> "SHA-1"
            64 -> "SHA-256"
            else -> return
        }
        val digest = java.security.MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw HosterException("Prüfsumme ($algorithm) stimmt nicht – Datei wird erneut geladen")
        }
    }

    /** Neuen Dateinamen speichern und eine vorhandene Teildatei mit umbenennen. */
    private suspend fun adoptFileName(item: DownloadItem, name: String): DownloadItem {
        val old = tempFile(item)
        val updated = item.copy(fileName = name)
        val renamed = tempFile(updated)
        if (old.path != renamed.path && old.exists()) old.renameTo(renamed)
        dao.setFileName(item.id, name)
        // Mit bekanntem Namen blockiert dieser Eintrag ein wartendes Archiv-Set
        // des Pakets vielleicht nicht mehr (z.B. readme.nfo statt part3)
        retryWaitingSets(item.packageId)
        return updated
    }

    /**
     * Datei in einen MediaStore-Eintrag kopieren. Ohne Ausgabestream oder bei
     * einem Fehler mitten im Kopieren wird der (halbe) Eintrag wieder
     * geloescht - sonst bliebe eine leere Datei in "Downloads" zurueck und die
     * Quelle wuerde trotzdem entfernt.
     */
    private fun copyToMediaStore(resolver: android.content.ContentResolver, uri: android.net.Uri, source: File): Boolean {
        try {
            val out = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            out.use { o -> source.inputStream().use { it.copyTo(o) } }
            return true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
    }

    /** Verschiebt die fertige Datei ins Ziel (oeffentlicher Download-Ordner oder App-Ordner). */
    private suspend fun finish(temp: File, fileName: String): String {
        targetTree()?.let { root ->
            copyToTree(root, temp, fileName)?.let { path ->
                temp.delete()
                return path
            }
            // Kopieren fehlgeschlagen (Berechtigung weg?) -> regulaerer Weg
        }
        val export = app.settings.currentExportToDownloads() && Build.VERSION.SDK_INT >= 29
        if (export) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JDAndroid")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null && copyToMediaStore(resolver, uri, temp)) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    temp.delete()
                    return "Downloads/JDAndroid/$fileName"
                }
            } catch (_: Exception) {
                // Export fehlgeschlagen -> Datei bleibt im App-Ordner
            }
        }
        // Liegt die Datei bereits unter ihrem Zielnamen im App-Ordner ("Erneut
        // laden" mit vorhandener Datei), nicht in "name (2)" umbenennen
        if (temp.path == File(downloadDir(), fileName).path) return temp.absolutePath
        val dest = FileNames.uniqueFile(downloadDir(), fileName)
        if (temp.path != dest.path) {
            temp.renameTo(dest)
        }
        return dest.absolutePath
    }

    internal companion object {
        /** Maximale automatische Wiederholversuche je Download. */
        const val MAX_ATTEMPTS = 5

        /** Hinweis an fertigen Archiv-Teilen, solange andere Teile noch laden. */
        const val WAITING_NOTE = "Warte auf weitere Archiv-Teile"

        /** Wartezeit vor dem [attempt]-ten Wiederholversuch: 10 s, 20 s, ... hoechstens 5 min. */
        fun backoffMillis(attempt: Int): Long {
            val base = 10_000L shl (attempt - 1).coerceAtMost(5)
            return base.coerceAtMost(5 * 60_000L)
        }

        /** Ab so viel neuem Fortschritt gilt ein Versuch als erfolgreich. */
        const val PROGRESS_RESET_BYTES = 4L * 1024 * 1024

        /** Abstand der Messpunkte fuer die Geschwindigkeit. */
        const val SAMPLE_MS = 500L

        /** Fenster des gleitenden Durchschnitts. */
        const val SPEED_WINDOW_MS = 5_000L

        /** Abstand der Fortschritts-Schreibvorgaenge in die Datenbank. */
        const val DB_WRITE_MS = 2_000L

        /** Mindestabstand der Benachrichtigungs-Aktualisierung. */
        const val NOTIFY_MS = 1_000L
    }
}
