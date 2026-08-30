package com.jdandroid.engine

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jdandroid.JdApp
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.PackageNaming
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.HosterRegistry
import com.jdandroid.hoster.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val jobs = HashMap<Long, Job>()
    private val mutex = Mutex()

    /**
     * Serialisiert den Download-Abschluss: verhindert, dass zwei gleichzeitig
     * fertige Teile desselben Archivs sich gegenseitig als "noch ausstehend"
     * sehen und das Entpacken dadurch ganz ausbleibt.
     */
    private val completionMutex = Mutex()

    private val limiter = SpeedLimiter()

    init {
        // Geschwindigkeitslimit aus den Einstellungen live uebernehmen
        scope.launch {
            app.settings.speedLimitKbps.collect { limiter.limitBps = it.toLong() * 1024 }
        }
    }

    val activeCount: Int get() = jobs.size

    private fun downloadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
            .apply { mkdirs() }

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
        if (blockedByMeteredNetwork(app.settings.currentWifiOnly())) {
            onStateChanged()
            return
        }
        val max = app.settings.currentMaxConcurrent()
        mutex.withLock {
            while (jobs.size < max) {
                val next = dao.nextQueued() ?: break
                dao.setStatus(next.id, DownloadStatus.RUNNING)
                jobs[next.id] = scope.launch { run(next.id) }
            }
        }
        onStateChanged()
    }

    suspend fun pause(id: Long) {
        mutex.withLock { jobs.remove(id) }?.cancel()
        val item = dao.byId(id) ?: return
        if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.QUEUED) {
            dao.setStatus(id, DownloadStatus.PAUSED)
        }
        pump()
    }

    suspend fun cancelAndDelete(id: Long) {
        mutex.withLock { jobs.remove(id) }?.cancel()
        dao.byId(id)?.let { item ->
            tempFile(item).delete()
        }
        dao.delete(id)
        pump()
    }

    suspend fun pauseAll() {
        val running = mutex.withLock {
            val copy = jobs.toMap()
            jobs.clear()
            copy
        }
        running.values.forEach { it.cancel() }
        running.keys.forEach { dao.setStatus(it, DownloadStatus.PAUSED) }
        onStateChanged()
    }

    private fun tempFile(item: DownloadItem): File {
        val name = item.fileName ?: "download-${item.id}"
        return File(downloadDir(), "${item.id}-$name.part")
    }

    private suspend fun run(id: Long) {
        try {
            val item = dao.byId(id) ?: return
            val hoster = HosterRegistry.byId(item.hosterId)
                ?: throw HosterException("Unbekannter Hoster", true)
            val account = accountDao.validForHoster(item.hosterId)
            val resolved = hoster.resolve(item.url, account)

            var current = dao.byId(id) ?: return
            if (current.fileName == null && resolved.fileName != null) {
                current = current.copy(fileName = sanitizeFileName(resolved.fileName))
                dao.update(current)
                refinePackageName(current.packageId)
            }
            download(current, resolved.directUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HosterException) {
            if (e.permanent) {
                dao.setStatus(id, DownloadStatus.FAILED, e.message)
            } else {
                handleTransientFailure(id, e.message ?: "Fehler")
            }
        } catch (e: Exception) {
            // Netzwerkfehler und Abbrueche sind typischerweise voruebergehend
            handleTransientFailure(id, e.message ?: e.javaClass.simpleName)
        } finally {
            mutex.withLock { jobs.remove(id) }
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

    private fun backoffMillis(attempt: Int): Long {
        val base = 10_000L shl (attempt - 1).coerceAtMost(5)
        return base.coerceAtMost(5 * 60_000L)
    }

    /**
     * Sobald Dateinamen bekannt sind, wird ein automatisch benanntes Paket
     * nach dem gemeinsamen Namensteil benannt - wie im JDownloader.
     */
    private suspend fun refinePackageName(packageId: Long?) {
        val id = packageId ?: return
        val names = dao.byPackage(id).mapNotNull { it.fileName }
        val name = PackageNaming.commonName(names) ?: return
        app.db.packageDao().refineAutoName(id, name)
    }

    private suspend fun download(item: DownloadItem, directUrl: String) {
        var target = tempFile(item)
        var offset = if (target.exists()) target.length() else 0L

        val builder = Request.Builder()
            .url(directUrl)
            .header("User-Agent", Http.USER_AGENT)
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        Http.client.newCall(builder.build()).execute().use { resp ->
            // 416: angefragter Bereich hinter Dateiende -> Datei war schon vollstaendig
            if (resp.code == 416 && offset > 0) {
                dao.updateProgress(item.id, offset, offset, 0)
                return@use
            }
            if (!resp.isSuccessful) {
                throw HosterException("Server antwortete mit HTTP ${resp.code}")
            }
            // Server ignoriert Range -> von vorn beginnen
            if (offset > 0 && resp.code != 206) {
                target.delete()
                offset = 0
            }
            val body = resp.body ?: throw HosterException("Leere Antwort beim Download")
            val total = if (body.contentLength() >= 0) body.contentLength() + offset else item.fileSize

            // Speicherplatz vorab pruefen: sonst bricht der Download erst nach
            // Minuten mit einem nichtssagenden IO-Fehler ab.
            val needed = if (total > 0) total - offset else 0
            val free = downloadDir().usableSpace
            if (needed > 0 && free in 0 until needed) {
                throw HosterException(
                    "Zu wenig Speicherplatz: ${needed / (1 shl 20)} MB benötigt, " +
                        "${free / (1 shl 20)} MB frei",
                    permanent = true
                )
            }

            // Dateiname ggf. aus Content-Disposition oder URL ableiten
            var current = dao.byId(item.id) ?: return
            if (current.fileName == null) {
                val name = fileNameFrom(resp.header("Content-Disposition"), directUrl)
                current = current.copy(fileName = name)
                dao.update(current)
                val renamed = tempFile(current)
                if (target.path != renamed.path && target.exists()) target.renameTo(renamed)
                target = renamed
            }

            var written = offset
            var lastUpdate = 0L
            var lastBytes = written
            body.byteStream().use { input ->
                java.io.FileOutputStream(target, offset > 0).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        limiter.throttle(read)
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 750) {
                            val speed = (written - lastBytes) * 1000 / (now - lastUpdate).coerceAtLeast(1)
                            dao.updateProgress(item.id, written, total, if (lastUpdate == 0L) 0 else speed)
                            lastUpdate = now
                            lastBytes = written
                            onStateChanged()
                        }
                        kotlinx.coroutines.yield()
                    }
                }
            }
            dao.updateProgress(item.id, written, total, 0)
            if (total > 0 && written < total) {
                throw IOException("Download unvollständig ($written von $total Bytes)")
            }
        }

        val finalName = dao.byId(item.id)?.fileName ?: target.name.removeSuffix(".part")
        completeDownload(item.id, target, finalName)
        onStateChanged()
    }

    /**
     * Abschluss eines Downloads: Archive werden (wenn aktiviert) automatisch
     * entpackt, sobald alle Teile vorliegen; alles andere wird direkt exportiert.
     */
    private suspend fun completeDownload(id: Long, temp: File, fileName: String) = completionMutex.withLock {
        val base = Extractor.archiveBase(fileName)
        val autoExtract = app.settings.currentAutoExtract()

        if (!autoExtract || base == null) {
            val path = finish(temp, fileName)
            markCompleted(id, path, null)
            return@withLock
        }

        // Archiv-Volume unter echtem Namen im App-Ordner ablegen, damit
        // Multipart-Teile zueinander finden
        val archiveFile = File(downloadDir(), fileName)
        if (temp.path != archiveFile.path) {
            archiveFile.delete()
            temp.renameTo(archiveFile)
        }

        // Warten noch andere Teile desselben Archivs? Dann erst mal fertig melden.
        val pending = dao.all().any { other ->
            other.id != id &&
                other.status in listOf(
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING,
                    DownloadStatus.PAUSED, DownloadStatus.EXTRACTING
                ) &&
                Extractor.archiveBase(other.fileName ?: "") == base
        }
        if (pending) {
            markCompleted(id, archiveFile.absolutePath, "Warte auf weitere Archiv-Teile")
            return@withLock
        }

        val primary = Extractor.findPrimaryVolume(downloadDir(), base)
        if (primary == null) {
            markCompleted(id, archiveFile.absolutePath, "Erstes Archiv-Teil fehlt, nicht entpackt")
            return@withLock
        }

        dao.setStatus(id, DownloadStatus.EXTRACTING)
        onStateChanged()
        try {
            val extractDir = File(downloadDir(), base)
            Extractor.extract(primary, extractDir, app.settings.currentPasswords())
            val exportedPath = exportDirectory(extractDir, base)
            if (app.settings.currentDeleteArchive()) {
                downloadDir().listFiles()
                    ?.filter { Extractor.archiveBase(it.name) == base }
                    ?.forEach { it.delete() }
            }
            markCompleted(id, exportedPath, null)
        } catch (e: Exception) {
            markCompleted(id, archiveFile.absolutePath, e.message)
        }
    }

    private suspend fun markCompleted(id: Long, path: String?, note: String?) {
        dao.byId(id)?.let {
            dao.update(
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    localPath = path,
                    speedBps = 0,
                    errorMessage = note,
                    attempts = 0,
                    retryAt = 0
                )
            )
        }
    }

    /**
     * Exportiert alle entpackten Dateien in den oeffentlichen Download-Ordner
     * (Downloads/JDAndroid/<base>/...). Ohne Export bleiben sie im App-Ordner.
     */
    private suspend fun exportDirectory(dir: File, base: String): String {
        val export = app.settings.currentExportToDownloads() && Build.VERSION.SDK_INT >= 29
        if (!export) return dir.absolutePath
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
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    file.delete()
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

    private fun fileNameFrom(contentDisposition: String?, url: String): String {
        contentDisposition?.let {
            Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""")
                .find(it)?.groupValues?.get(1)?.let { name ->
                    return sanitizeFileName(java.net.URLDecoder.decode(name.trim(), "UTF-8"))
                }
        }
        return sanitizeFileName(url.substringBefore('?').substringAfterLast('/'))
    }

    /** Server-gelieferte Namen bereinigen (Pfad-Traversal, verbotene Zeichen). */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|]"""), "_")
            .trim()
            .trimStart('.')
            .ifBlank { "download.bin" }

    /** Verschiebt die fertige Datei ins Ziel (oeffentlicher Download-Ordner oder App-Ordner). */
    private suspend fun finish(temp: File, fileName: String): String {
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
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    }
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
        val dest = uniqueFile(downloadDir(), fileName)
        if (temp.path != dest.path) {
            temp.renameTo(dest)
        }
        return dest.absolutePath
    }

    /**
     * Liefert einen freien Dateinamen: "film.mkv" -> "film (2).mkv".
     * Vorher wurde eine bereits vorhandene Datei kommentarlos überschrieben.
     */
    private fun uniqueFile(dir: File, fileName: String): File {
        val candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var index = 2
        while (index < 1000) {
            val next = File(dir, "$base ($index)$suffix")
            if (!next.exists()) return next
            index++
        }
        return File(dir, "$base (${System.currentTimeMillis()})$suffix")
    }

    private companion object {
        /** Maximale automatische Wiederholversuche je Download. */
        const val MAX_ATTEMPTS = 5
    }
}
