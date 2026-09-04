package com.jdandroid.engine

import com.jdandroid.core.ArchiveNames
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Entpackt Archive nach dem Download. Passwoerter werden wie im JDownloader
 * der Reihe nach aus der Passwortliste probiert (zuerst ohne Passwort).
 */
object Extractor {

    private val archiveExtensions = listOf(".zip", ".7z", ".rar")

    /** Fortschritt hoechstens alle 4 MiB melden. */
    private const val PROGRESS_STEP = 4L * 1024 * 1024

    @Volatile
    private var sevenZipReady = false

    /**
     * Laedt die native 7-Zip-Bibliothek. Auf Android greift die
     * Auto-Initialisierung von 7-Zip-JBinding nicht: sie sucht eine
     * Platform-JAR, die es im APK nicht gibt. Die .so liegt stattdessen im
     * lib-Verzeichnis des APK und muss selbst geladen werden, bevor
     * initLoadedLibraries() die Bindung herstellt.
     */
    @Synchronized
    private fun ensureSevenZip() {
        if (sevenZipReady) return
        try {
            System.loadLibrary("7-Zip-JBinding")
            SevenZip.initLoadedLibraries()
            sevenZipReady = true
        } catch (e: Throwable) {
            throw IOException(
                "Native 7-Zip-Bibliothek konnte nicht geladen werden " +
                    "(RAR-Entpacken nicht moeglich): ${e.message}"
            )
        }
    }

    /** Siehe [ArchiveNames.repairName]. */
    fun repairName(name: String): String = ArchiveNames.repairName(name)

    /** Archivformat anhand der ersten Bytes: "rar", "zip", "7z" oder null. */
    fun sniffExtension(file: File): String? {
        val head = ByteArray(8)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n < 6) return null
        fun at(i: Int) = head[i].toInt() and 0xFF
        return when {
            at(0) == 'R'.code && at(1) == 'a'.code && at(2) == 'r'.code && at(3) == '!'.code &&
                at(4) == 0x1A && at(5) == 0x07 -> "rar"
            at(0) == 'P'.code && at(1) == 'K'.code && at(2) == 3 && at(3) == 4 -> "zip"
            at(0) == '7'.code && at(1) == 'z'.code && at(2) == 0xBC && at(3) == 0xAF &&
                at(4) == 0x27 && at(5) == 0x1C -> "7z"
            else -> null
        }
    }

    fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return archiveExtensions.any { lower.endsWith(it) }
    }

    /** Siehe [ArchiveNames.isSecondaryVolume]. */
    fun isSecondaryVolume(fileName: String): Boolean = ArchiveNames.isSecondaryVolume(fileName)

    /**
     * Entpackt [archive] nach [destDir]. Probiert erst ohne Passwort, dann
     * alle Eintraege aus [passwords]. Liefert das verwendete Passwort (oder
     * null bei unverschluesseltem Archiv), wirft [IOException] wenn nichts passt.
     */
    /**
     * Ausschlussmuster wie im JDownloader ("*.nfo", "*sample*", "proof/…"):
     * * steht fuer beliebig viele Zeichen, ? fuer eines; Vergleich ohne
     * Beachtung der Gross-/Kleinschreibung, gegen den Dateinamen und den
     * vollstaendigen Pfad im Archiv.
     */
    fun isExcluded(entryPath: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        val path = entryPath.replace('\\', '/').trimStart('/')
        val name = path.substringAfterLast('/')
        return patterns.any { pattern ->
            val p = pattern.trim()
            if (p.isEmpty()) return@any false
            val regex = buildString {
                for (c in p) {
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            }.toRegex(RegexOption.IGNORE_CASE)
            regex.matches(name) || regex.matches(path)
        }
    }

    /** Fortschrittsmeldung: entpackte Bytes und Gesamtbytes (0 = unbekannt). */
    fun interface ProgressListener {
        fun onProgress(done: Long, total: Long)
    }

    fun extract(
        archive: File,
        destDir: File,
        passwords: List<String>,
        excludes: List<String> = emptyList(),
        flat: Boolean = false,
        progress: ProgressListener? = null
    ): String? {
        destDir.mkdirs()
        // Jeder Versuch laeuft in ein eigenes Arbeitsverzeichnis: destDir ist der
        // Paketordner und kann bereits Dateien anderer Archive enthalten, die ein
        // Fehlversuch nicht mitloeschen darf. Erst bei Erfolg wandert der Inhalt
        // nach destDir (gleiche Partition, daher renameTo).
        val workDir = File(destDir, ".extract-" + (archiveBase(archive.name) ?: archive.name))
        val candidates = listOf<String?>(null) + passwords.filter { it.isNotBlank() }
        var lastError: Exception? = null
        try {
            for (password in candidates) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                try {
                    val lower = archive.name.lowercase()
                    when {
                        lower.endsWith(".zip") -> extractZip(archive, workDir, password, excludes, progress, flat)
                        lower.endsWith(".7z") || Regex("""\.7z\.\d+$""").containsMatchIn(lower) ->
                            extractSevenZip(archive, workDir, password, excludes, progress, flat)
                        lower.endsWith(".rar") -> extractRar(archive, workDir, password, excludes, progress, flat)
                        else -> throw IOException("Unbekanntes Archivformat: ${archive.name}")
                    }
                    workDir.listFiles()?.forEach { moveInto(it, File(destDir, it.name)) }
                    return password
                } catch (e: Exception) {
                    lastError = e
                }
            }
        } finally {
            // Nur die Reste des eigenen Versuchs entfernen, nie fremde Dateien in destDir
            workDir.deleteRecursively()
        }
        throw IOException(
            "Entpacken fehlgeschlagen (Passwort nicht in der Liste?): ${lastError?.message}"
        )
    }

    /**
     * Verschiebt [src] nach [dst]. Bestehende Ordner werden zusammengefuehrt,
     * bestehende Dateien ersetzt; schlaegt renameTo fehl, wird kopiert.
     */
    private fun moveInto(src: File, dst: File) {
        if (src.isDirectory && dst.isDirectory) {
            src.listFiles()?.forEach { moveInto(it, File(dst, it.name)) }
            src.delete()
            return
        }
        if (dst.exists()) dst.deleteRecursively()
        if (!src.renameTo(dst)) {
            if (!src.copyRecursively(dst, overwrite = true)) {
                throw IOException("Konnte ${src.name} nicht nach ${dst.parent} verschieben")
            }
            src.deleteRecursively()
        }
    }

    private fun extractZip(
        archive: File, destDir: File, password: String?, excludes: List<String>, progress: ProgressListener?,
        flat: Boolean
    ) {
        val zip = ZipFile(archive)
        if (zip.isEncrypted) {
            if (password == null) throw ZipException("Passwort erforderlich")
            zip.setPassword(password.toCharArray())
        } else if (password != null) {
            // unverschluesselt wurde bereits im ersten Durchlauf probiert
            throw ZipException("Kein Passwort nötig")
        }
        // Datei fuer Datei statt extractAll: so greifen Ausschlussmuster und
        // der Fortschritt laesst sich melden
        val headers = zip.fileHeaders.filter { !it.isDirectory && !isExcluded(it.fileName, excludes) }
        val total = headers.sumOf { it.uncompressedSize.coerceAtLeast(0) }
        var done = 0L
        val used = HashSet<String>()
        progress?.onProgress(0, total)
        for (header in headers) {
            val out = targetFile(destDir, header.fileName, flat, used)
            if (flat) zip.extractFile(header, destDir.absolutePath, out.name)
            else zip.extractFile(header, destDir.absolutePath)
            done += header.uncompressedSize.coerceAtLeast(0)
            progress?.onProgress(done, total)
        }
    }

    /** Alle Volumes eines mehrteiligen 7z (.7z.001, .7z.002 ...) in Reihenfolge. */
    internal fun sevenZVolumes(archive: File): List<File> {
        val m = Regex("""^(.*)\.7z\.(\d+)$""", RegexOption.IGNORE_CASE).find(archive.name)
            ?: return listOf(archive)
        val prefix = m.groupValues[1]
        val pattern = Regex("""^${Regex.escape(prefix)}\.7z\.(\d+)$""", RegexOption.IGNORE_CASE)
        return archive.parentFile?.listFiles()
            ?.filter { pattern.matches(it.name) }
            ?.sortedBy { pattern.find(it.name)!!.groupValues[1].toInt() }
            ?.ifEmpty { listOf(archive) }
            ?: listOf(archive)
    }

    private fun extractSevenZip(
        archive: File, destDir: File, password: String?, excludes: List<String>, progress: ProgressListener?,
        flat: Boolean
    ) {
        val volumes = sevenZVolumes(archive)
        val builder = SevenZFile.builder()
        if (volumes.size > 1) {
            // Mehrteilig: alle Teile als ein zusammenhaengender Kanal
            builder.setSeekableByteChannel(MultiReadOnlySeekableByteChannel.forFiles(*volumes.toTypedArray()))
        } else {
            builder.setFile(archive)
        }
        if (password != null) builder.setPassword(password.toCharArray())
        builder.get().use { sevenZ ->
            val total = sevenZ.entries
                .filter { !it.isDirectory && !isExcluded(it.name, excludes) }
                .sumOf { it.size.coerceAtLeast(0) }
            var done = 0L
            var lastReport = 0L
            val used = HashSet<String>()
            progress?.onProgress(0, total)
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (isExcluded(entry.name, excludes)) continue
                if (entry.isDirectory) {
                    if (!flat) safeChild(destDir, entry.name).mkdirs()
                    continue
                }
                val out = targetFile(destDir, entry.name, flat, used)
                out.parentFile?.mkdirs()
                out.outputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = sevenZ.read(buffer)
                        if (read < 0) break
                        stream.write(buffer, 0, read)
                        done += read
                        if (done - lastReport >= PROGRESS_STEP) {
                            lastReport = done
                            progress?.onProgress(done, total)
                        }
                    }
                }
            }
            progress?.onProgress(done, total)
        }
    }

    /**
     * RAR ueber das native 7-Zip-Binding: unterstuetzt RAR4 und RAR5,
     * jeweils inkl. Verschluesselung und Multivolume (.partN.rar).
     */
    private fun extractRar(
        archive: File, destDir: File, password: String?, excludes: List<String>, progress: ProgressListener?,
        flat: Boolean
    ) {
        ensureSevenZip()
        val openCallback = RarOpenCallback(archive, password)
        RandomAccessFile(archive, "r").use { raf ->
            val inArchive = SevenZip.openInArchive(
                null, RandomAccessFileInStream(raf), openCallback
            )
            try {
                val items = inArchive.simpleInterface.archiveItems.filter { item ->
                    val path = item.path
                    path != null && !item.isFolder && !isExcluded(path, excludes)
                }
                val total = items.sumOf { (it.size ?: 0L).coerceAtLeast(0) }
                var done = 0L
                var lastReport = 0L
                val used = HashSet<String>()
                progress?.onProgress(0, total)
                for (item in inArchive.simpleInterface.archiveItems) {
                    val path = item.path ?: continue
                    if (isExcluded(path, excludes)) continue
                    if (item.isFolder) {
                        if (!flat) safeChild(destDir, path).mkdirs()
                        continue
                    }
                    val out = targetFile(destDir, path, flat, used)
                    out.parentFile?.mkdirs()
                    val result = out.outputStream().use { stream ->
                        val sink = net.sf.sevenzipjbinding.ISequentialOutStream { data ->
                            stream.write(data)
                            done += data.size
                            if (done - lastReport >= PROGRESS_STEP) {
                                lastReport = done
                                progress?.onProgress(done, total)
                            }
                            data.size
                        }
                        if (password != null) item.extractSlow(sink, password)
                        else item.extractSlow(sink)
                    }
                    if (result != ExtractOperationResult.OK) {
                        throw IOException("RAR-Extraktion: $result")
                    }
                }
                progress?.onProgress(done, total)
            } finally {
                runCatching { inArchive.close() }
                openCallback.close()
            }
        }
    }

    /** Liefert weitere Volumes eines Multipart-RAR und das Passwort beim Oeffnen. */
    private class RarOpenCallback(
        private val primary: File,
        private val password: String?
    ) : IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword {

        private val volumes = HashMap<String, RandomAccessFile>()
        private var lastName: String = primary.absolutePath

        override fun setTotal(files: Long?, bytes: Long?) {}
        override fun setCompleted(files: Long?, bytes: Long?) {}

        override fun getProperty(propID: PropID): Any? =
            if (propID == PropID.NAME) lastName else null

        override fun getStream(filename: String): IInStream? {
            val file = File(primary.parentFile, File(filename).name)
            if (!file.exists()) return null
            lastName = file.absolutePath
            val raf = volumes.getOrPut(file.name) { RandomAccessFile(file, "r") }
            raf.seek(0)
            return RandomAccessFileInStream(raf)
        }

        override fun cryptoGetTextPassword(): String = password ?: ""

        fun close() {
            volumes.values.forEach { runCatching { it.close() } }
        }
    }

    /** Siehe [ArchiveNames.archiveBase]. */
    fun archiveBase(fileName: String): String? = ArchiveNames.archiveBase(fileName)

    /** Erstes/primaeres Volume eines Archivs im Verzeichnis finden. */
    fun findPrimaryVolume(dir: File, base: String): File? =
        dir.listFiles()?.firstOrNull { file ->
            val lower = file.name.lowercase()
            archiveBase(file.name) == base && !isSecondaryVolume(file.name) &&
                (lower.endsWith(".rar") || lower.endsWith(".zip") ||
                    lower.endsWith(".7z") || lower.endsWith(".7z.001"))
        }

    /**
     * Zieldatei eines Archiveintrags. Flach: die Ordner im Archiv werden
     * ignoriert, jede Datei landet direkt in [destDir]; gleiche Namen aus
     * verschiedenen Archivordnern bekommen "(2)", "(3)" ... angehaengt.
     * [used] merkt sich die in diesem Lauf vergebenen Namen.
     */
    internal fun targetFile(destDir: File, entryPath: String, flat: Boolean, used: MutableSet<String>): File {
        if (!flat) return safeChild(destDir, entryPath)
        val name = entryPath.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { "datei" }
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var candidate = name
        var n = 2
        while (candidate.lowercase() in used || File(destDir, candidate).exists()) {
            candidate = "$base ($n)$ext"
            n++
        }
        used += candidate.lowercase()
        return safeChild(destDir, candidate)
    }

    /** Schutz gegen Zip-Slip: Zielpfad muss innerhalb von destDir bleiben. */
    private fun safeChild(destDir: File, name: String): File {
        val target = File(destDir, name)
        if (!target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
            throw IOException("Ungültiger Pfad im Archiv: $name")
        }
        return target
    }
}
