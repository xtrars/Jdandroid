package com.jdandroid.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jdandroid.JdApp
import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.Clock
import com.jdandroid.core.FileNames
import com.jdandroid.core.FreeMode
import com.jdandroid.core.LiveProgress
import com.jdandroid.core.ProgressBus
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.PackageNaming
import com.jdandroid.data.renameFile
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.HosterRegistry
import com.jdandroid.hoster.Http
import com.jdandroid.hoster.WaitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Fuehrt die eigentlichen Downloads aus: Link aufloesen, Datei mit
 * Range-Resume herunterladen, Fortschritt ueber den [ProgressBus] melden;
 * Abschluss, Entpacken und Export uebernimmt der [ArchiveCoordinator], die
 * Ablageorte kennt [StorageTarget], den Free-Modus-Ablauf der [FreeFlow].
 *
 * In die Datenbank gehen nur Zustandswechsel (Start, Pause, Fehler,
 * Abschluss, Entpack-Start/-Ende, jeweils mit dem letzten Bytestand) und
 * eine Sicherung des Bytestands hoechstens alle [SAVE_MS]; Live-Werte
 * (Bytes, Geschwindigkeit, Entpack-Prozent) liegen nur im Bus.
 */
class DownloadEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Monotone Uhr fuer Messungen und Drosselung; Tests koennen sie vorstellen. */
    private val clock: Clock = Clock.SYSTEM,
    private val onStateChanged: () -> Unit
) {
    private val app = context.applicationContext as JdApp
    private val dao = app.db.downloadDao()
    private val accountDao = app.db.accountDao()

    // ConcurrentHashMap: size() wird vom Service-Thread ohne Mutex gelesen
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val mutex = Mutex()

    /** Letzte Benachrichtigung des Service (monotone Uhr), um Aktualisierungen zu drosseln. */
    @Volatile
    private var lastNotify = clock.nowMillis() - NOTIFY_MS

    private val storage = StorageTarget(context, app.settings)
    private val freeFlow = FreeFlow(dao, accountDao, app.settings)
    /** Abschluss fertiger Downloads, Archiv-Sets und Entpacken. */
    private val archives = ArchiveCoordinator(
        app, dao, app.settings, storage, scope, clock, onStateChanged
    ) { scope.launch { pump() } }
    /** Abschluss-Sperre des Koordinators, siehe [ArchiveCoordinator.completionMutex]. */
    private val completionMutex get() = archives.completionMutex

    /**
     * Startsperre: pump() wartet, bis der Dienst haengen gebliebene Eintraege
     * zurueckgesetzt hat. Vorher konnte ein frueher pump() (Netzwerk-Callback)
     * einen Eintrag starten, den requeueRunning() danach erneut einreihte -
     * derselbe Download lief zweimal.
     */
    private val startGate = CompletableDeferred<Unit>()

    private val limiter = SpeedLimiter(clock)

    init {
        // Geschwindigkeitslimit aus den Einstellungen live uebernehmen
        scope.launch {
            app.settings.speedLimitMbit.collect {
                limiter.limitBps = com.jdandroid.data.SettingsRepository.mbitToBytesPerSecond(it)
            }
        }
    }

    /** Laufende Downloads plus laufende Entpackvorgaenge. */
    val activeCount: Int get() = jobs.size + archives.activeCount

    fun markReady() { startGate.complete(Unit) }

    /** Nichts laeuft und nichts wartet - unter der Sperre, damit pump() nicht dazwischenfunkt. */
    suspend fun isIdle(): Boolean = mutex.withLock {
        // Eintraege, die auf ein Captcha warten, brauchen keinen laufenden
        // Dienst: "Captcha loesen" startet ihn bei Bedarf neu
        jobs.isEmpty() && archives.activeCount == 0 &&
            dao.queuedCountDue(System.currentTimeMillis() + FreeMode.USER_ACTION_HORIZON_MS) == 0
    }

    /** Summe der aktuellen Geschwindigkeiten, fuer die Benachrichtigung. */
    val totalSpeedBps: Long get() = ProgressBus.totalSpeedBps()

    /**
     * Geladene Bytes aller offenen Eintraege fuer die Benachrichtigung: fuer
     * laufende Downloads der Live-Stand aus dem Bus, fuer die uebrigen die
     * (bei Pause/Fehler gesicherte) Datenbank.
     */
    suspend fun openDownloadedBytes(): Long {
        val live = ProgressBus.state.value
        val liveBytes = live.values.sumOf { it.downloadedBytes.coerceAtLeast(0) }
        return dao.openDownloadedBytesExcept(live.keys.toList() + listOf(-1L)) + liveBytes
    }

    /** Service hoechstens einmal pro Sekunde ueber Fortschritt informieren. */
    private fun notifyProgress() {
        val now = clock.nowMillis()
        if (now - lastNotify >= NOTIFY_MS) {
            lastNotify = now
            onStateChanged()
        }
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
        armRetryTimer()
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
            ProgressBus.removeAll(running.keys)
            // Unter der Abschluss-Sperre: ein Job im NonCancellable-Abschluss
            // (Datei wird gerade verschoben) darf nicht mehr zurueckgestuft werden
            completionMutex.withLock { running.keys.forEach { dao.requeueIfRunning(it) } }
            onStateChanged()
        } else {
            pump()
        }
    }

    suspend fun pause(id: Long) {
        // Bus nur fuer den abgebrochenen Job leeren: ein Eintrag, der gerade
        // entpackt wird, behaelt seinen Prozentwert (pauseIfActive greift dort nicht)
        mutex.withLock { jobs.remove(id) }?.let { it.cancel(); ProgressBus.remove(id) }
        // Nur RUNNING/QUEUED pausieren - ein Eintrag, der gerade fertig wird
        // oder entpackt, bleibt unberuehrt (sonst Neudownload beim Fortsetzen).
        // Unter der Abschluss-Sperre, damit ein laufender Abschluss zu Ende kommt
        completionMutex.withLock { dao.pauseIfActive(id) }
        pump()
    }

    suspend fun cancelAndDelete(id: Long) {
        mutex.withLock { jobs.remove(id) }?.cancel()
        ProgressBus.remove(id)
        FreeDownloads.forget(id)
        val item = dao.byId(id)
        dao.delete(id)
        item?.let {
            tempFile(it).delete()
            // Archivteile im App-Ordner mit entfernen, wenn kein anderer Eintrag
            // (auch keines anderen Pakets - der Ordner ist flach) dasselbe
            // Archiv referenziert; sonst bleiben sie unsichtbar liegen
            val key = it.archiveKey
            if (key != null && dao.countByArchiveKey(key) == 0) {
                storage.downloadDir().listFiles()
                    ?.filter { f -> f.isFile && ArchiveNames.archiveBase(f.name) == key }
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
        ProgressBus.removeAll(running.keys)
        // Entpackende Eintraege bleiben EXTRACTING (laufen unter NonCancellable weiter)
        completionMutex.withLock { running.keys.forEach { dao.pauseIfActive(it) } }
        onStateChanged()
    }

    private fun tempFile(item: DownloadItem): File {
        val name = item.fileName ?: "download-${item.id}"
        return File(storage.downloadDir(), "${item.id}-$name.part")
    }

    private suspend fun run(id: Long) {
        try {
            val item = dao.byId(id) ?: return
            // Liegt das Archiv bereits vollstaendig im App-Ordner (z.B. nach
            // Pause waehrend des Entpackens), nicht erneut laden
            item.fileName?.let { name ->
                val existing = File(storage.downloadDir(), name)
                if (item.fileSize > 0 && existing.isFile && existing.length() == item.fileSize) {
                    archives.completeDownload(id, existing, name)
                    return
                }
            }
            val hoster = HosterRegistry.byId(item.hosterId)
                ?: throw HosterException("Unbekannter Hoster", true)
            // Premium- oder Free-Weg (Wartezeiten, Captcha) waehlt der FreeFlow
            val resolved = freeFlow.resolve(id, item, hoster)

            var current = dao.byId(id) ?: return
            val resolvedName = resolved.fileName?.let { FileNames.sanitize(it) }
            if (resolvedName != null && FileNames.preferName(current.fileName, resolvedName)) {
                current = adoptFileName(current, resolvedName)
                PackageNaming.refineAutoName(app.db, current.packageId)
            }
            download(current, resolved.directUrl, resolved.hash, resolved.headers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: WaitException) {
            freeFlow.scheduleWait(id, e.seconds, e.message)
        } catch (e: CaptchaRequiredException) {
            freeFlow.holdForCaptcha(id, e)
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
            // Die Live-Werte der Uebertragung raeumt transfer() selbst weg:
            // nach dem Abschluss gehoert der Bus-Eintrag ggf. dem Entpacken
            withContext(NonCancellable) { mutex.withLock { jobs.remove(id) } }
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
        // Den Folgeversuch stoesst der Timer aus pump() an (run() ruft pump() im finally)
    }

    /** Ausstehender Timer fuer das naechste retryAt und sein Zeitpunkt (unter [timerLock]). */
    private var retryTimer: Job? = null
    private var retryTimerAt = Long.MAX_VALUE
    private val timerLock = Any()

    /**
     * Timer auf das kleinste kuenftige retryAt stellen (Free-Wartezeit,
     * Backoff). Ein delay() im Dienst-Scope allein reicht nicht: stirbt der
     * Prozess waehrend einer stundenlangen Wartezeit, ruft der neue Dienst
     * nur einmal pump() - nextQueued() liefert den Eintrag noch nicht, und
     * nichts stiesse ihn nach Ablauf an. Deshalb liest pump() den Zeitpunkt
     * aus der Datenbank; es laeuft immer nur ein Timer, ein frueherer
     * Zeitpunkt ersetzt ihn. Captcha-Eintraege (jenseits des Horizonts)
     * bleiben unberuecksichtigt. retryAt ist ein persistierter Wanduhr-
     * Zeitstempel, deshalb wird hier bewusst gegen die Wanduhr gerechnet.
     */
    private suspend fun armRetryTimer() {
        val now = System.currentTimeMillis()
        val next = dao.nextRetryAt(now, now + FreeMode.USER_ACTION_HORIZON_MS) ?: return
        synchronized(timerLock) {
            if (retryTimer?.isActive == true && retryTimerAt <= next) return
            retryTimer?.cancel()
            retryTimerAt = next
            retryTimer = scope.launch {
                kotlinx.coroutines.delay((next - now + RETRY_TIMER_SLACK_MS).coerceAtLeast(0))
                // Sich selbst austragen, bevor pump() den naechsten Timer stellt:
                // sonst hielte pump() diesen (noch laufenden) Job fuer den aktuellen
                synchronized(timerLock) {
                    if (retryTimer === coroutineContext[Job]) {
                        retryTimer = null
                        retryTimerAt = Long.MAX_VALUE
                    }
                }
                pump()
            }
        }
    }

    private suspend fun download(
        item: DownloadItem,
        directUrl: String,
        expectedHash: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        var target = tempFile(item)
        var offset = if (target.exists()) target.length() else 0L

        val builder = Request.Builder()
            .url(directUrl)
            .header("User-Agent", Http.USER_AGENT)
            // Ohne gzip: sonst fehlt Content-Length und die Vollstaendigkeitspruefung
            .header("Accept-Encoding", "identity")
        // Vom Hoster mitgegebene Header (Free-Modus: Cookie, Referer); der
        // Hoster darf dabei auch die Browser-Kennung setzen
        headers.forEach { (name, value) -> builder.header(name, value) }
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
        archives.completeDownload(item.id, target, finalName)
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
                dao.saveProgress(item.id, offset, offset)
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
        // StatFs statt File.usableSpace: fragt das Dateisystem direkt
        val free = android.os.StatFs(storage.downloadDir().path).availableBytes
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
        // Monotone Uhr: eine Zeitkorrektur des Systems darf die Messung nicht verfaelschen
        val startedAt = clock.nowMillis()
        var lastSave = startedAt
        var attemptsReset = false
        // Start des Ladens: Bytestand und (jetzt bekannte) Groesse einmal
        // sichern - der einzige Zustandswechsel vor dem Ende der Uebertragung
        dao.saveProgress(item.id, written, total)
        // Geschwindigkeit als gleitender Durchschnitt ueber SPEED_WINDOW_MS:
        // der Rohwert einzelner Messintervalle springt stark und liess die
        // Anzeige flackern. Bytes und Geschwindigkeit gehen alle SAMPLE_MS in
        // den ProgressBus (nicht in die Datenbank); gesichert wird der
        // Bytestand hoechstens alle SAVE_MS und beim Verlassen der Schleife.
        val samples = ArrayDeque<Pair<Long, Long>>()
        samples.addLast(startedAt to written)
        try {
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
                        val now = clock.nowMillis()
                        if (now - samples.last().first >= SAMPLE_MS) {
                            samples.addLast(now to written)
                            while (samples.size > 2 && now - samples.first().first > SPEED_WINDOW_MS) {
                                samples.removeFirst()
                            }
                            val (t0, b0) = samples.first()
                            val speed = if (now > t0) (written - b0) * 1000 / (now - t0) else 0L
                            ProgressBus.update(item.id, LiveProgress(written, speed), now)
                            notifyProgress()
                            if (now - lastSave >= SAVE_MS) {
                                dao.saveProgress(item.id, written, total)
                                lastSave = now
                            }
                        }
                        kotlinx.coroutines.yield()
                    }
                }
            }
        } finally {
            // Letzten Bytestand auch bei Abbruch/Fehler sichern (Pause, Netz weg):
            // Fortsetzen nutzt zwar die .part-Groesse, die Liste soll aber
            // nicht auf den Stand von vor 30 s zurueckspringen. Danach die
            // Live-Werte weg: ab jetzt gilt wieder der Datenbankstand
            withContext(NonCancellable) {
                dao.saveProgress(item.id, written, total)
                ProgressBus.remove(item.id)
            }
        }
        if (total > 0 && written < total) {
            throw IOException("Download unvollständig ($written von $total Bytes)")
        }
        return true
    }

    /**
     * Nachtraegliches Entpacken eines fertigen Downloads (Aktionsmenue), siehe
     * [ArchiveCoordinator.extractNow]. Wartet die Startsperre ab, damit der
     * Dienst vorher haengen gebliebene Eintraege zurueckgesetzt hat.
     */
    suspend fun extractNow(id: Long): String? {
        startGate.await()
        return archives.extractNow(id)
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
        dao.renameFile(item.id, name)
        // Mit bekanntem Namen blockiert dieser Eintrag ein wartendes Archiv-Set
        // des Pakets vielleicht nicht mehr (z.B. readme.nfo statt part3)
        archives.retryWaitingSets(item.packageId)
        return updated
    }

    internal companion object {
        /** Maximale automatische Wiederholversuche je Download. */
        const val MAX_ATTEMPTS = 5

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

        /** Hoechstens so oft wird der Bytestand waehrend des Ladens in die Datenbank gesichert. */
        const val SAVE_MS = 30_000L

        /** Zuschlag auf den Timer fuer retryAt, damit nextQueued() den Eintrag sicher liefert. */
        const val RETRY_TIMER_SLACK_MS = 500L

        /** Mindestabstand der Benachrichtigungs-Aktualisierung. */
        const val NOTIFY_MS = 1_000L
    }
}
