package com.jdandroid.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jdandroid.JdApp
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
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

    val activeCount: Int get() = jobs.size

    private fun downloadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
            .apply { mkdirs() }

    /** Startet weitere Downloads, solange Slots frei sind. */
    suspend fun pump() {
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
                current = current.copy(fileName = resolved.fileName)
                dao.update(current)
            }
            download(current, resolved.directUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HosterException) {
            dao.setStatus(id, DownloadStatus.FAILED, e.message)
        } catch (e: Exception) {
            dao.setStatus(id, DownloadStatus.FAILED, e.message ?: e.javaClass.simpleName)
        } finally {
            mutex.withLock { jobs.remove(id) }
            scope.launch { pump() }
        }
    }

    private suspend fun download(item: DownloadItem, directUrl: String) {
        var target = tempFile(item)
        var offset = if (target.exists()) target.length() else 0L

        val builder = Request.Builder()
            .url(directUrl)
            .header("User-Agent", Http.USER_AGENT)
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        Http.client.newCall(builder.build()).execute().use { resp ->
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
    private suspend fun completeDownload(id: Long, temp: File, fileName: String) {
        val base = Extractor.archiveBase(fileName)
        val autoExtract = app.settings.currentAutoExtract()

        if (!autoExtract || base == null) {
            val path = finish(temp, fileName)
            markCompleted(id, path, null)
            return
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
            return
        }

        val primary = Extractor.findPrimaryVolume(downloadDir(), base)
        if (primary == null) {
            markCompleted(id, archiveFile.absolutePath, "Erstes Archiv-Teil fehlt, nicht entpackt")
            return
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
                    errorMessage = note
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
                    return java.net.URLDecoder.decode(name.trim(), "UTF-8")
                }
        }
        return url.substringBefore('?').substringAfterLast('/').ifBlank { "download.bin" }
    }

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
        val dest = File(downloadDir(), fileName)
        if (temp.path != dest.path) {
            dest.delete()
            temp.renameTo(dest)
        }
        return dest.absolutePath
    }
}
