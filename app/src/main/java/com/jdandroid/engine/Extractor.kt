package com.jdandroid.engine

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

    private val knownExtensions = setOf(
        "rar", "zip", "7z", "mkv", "mp4", "avi", "mov", "wmv", "iso", "img", "bin",
        "exe", "msi", "apk", "pdf", "epub", "mp3", "flac", "m4a", "txt", "nfo",
        "srt", "sfv", "par2", "tar", "gz", "bz2", "xz", "ts", "m2ts", "wav", "ogg"
    )

    /**
     * Namen reparieren, bei denen der Hoster Punkte und Bindestriche durch
     * Leerzeichen ersetzt hat ("Download name part1 rar" statt
     * "name.part1.rar"): ohne Endung erkennt die App weder Archiv noch
     * Zusammengehoerigkeit der Teile. Nur Namen ohne Punkt, deren letztes
     * Wort eine bekannte Endung ist; ein "Download "-Praefix faellt weg.
     */
    fun repairName(name: String): String {
        if (name.contains('.')) return name
        val tokens = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return name
        var words = tokens
        var ext = words.last().lowercase()
        // "name 7z 001" -> name.7z.001
        if (Regex("""^\d{3}$""").matches(ext) && words.size >= 3 && words[words.size - 2].equals("7z", true)) {
            words = words.dropLast(2)
            ext = "7z.$ext"
        } else if (ext in knownExtensions || Regex("""^[rz]\d{2}$""").matches(ext)) {
            words = words.dropLast(1)
        } else {
            return name
        }
        if (words.size > 1 && words.first().equals("Download", true)) words = words.drop(1)
        if (words.isEmpty()) return name
        return words.joinToString(".") + "." + ext
    }

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

    /**
     * Weitere Teile eines Multipart-Archivs (part2, .r00, .z01 …) nicht selbst
     * entpacken – das erledigt der erste Teil mit.
     */
    fun isSecondaryVolume(fileName: String): Boolean {
        val lower = fileName.lowercase()
        Regex("""\.part(\d+)\.rar$""").find(lower)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 1) > 1
        }
        Regex("""\.7z\.(\d+)$""").find(lower)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 1) > 1
        }
        return Regex("""\.[rz]\d{2}$""").containsMatchIn(lower)
    }

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

    fun extract(
        archive: File,
        destDir: File,
        passwords: List<String>,
        excludes: List<String> = emptyList()
    ): String? {
        destDir.mkdirs()
        val candidates = listOf<String?>(null) + passwords.filter { it.isNotBlank() }
        var lastError: Exception? = null
        for (password in candidates) {
            try {
                val lower = archive.name.lowercase()
                when {
                    lower.endsWith(".zip") -> extractZip(archive, destDir, password, excludes)
                    lower.endsWith(".7z") || Regex("""\.7z\.\d+$""").containsMatchIn(lower) ->
                        extractSevenZip(archive, destDir, password, excludes)
                    lower.endsWith(".rar") -> extractRar(archive, destDir, password, excludes)
                    else -> throw IOException("Unbekanntes Archivformat: ${archive.name}")
                }
                return password
            } catch (e: Exception) {
                lastError = e
                // Reste eines fehlgeschlagenen Versuchs entfernen
                destDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        }
        throw IOException(
            "Entpacken fehlgeschlagen (Passwort nicht in der Liste?): ${lastError?.message}"
        )
    }

    private fun extractZip(archive: File, destDir: File, password: String?, excludes: List<String>) {
        val zip = ZipFile(archive)
        if (zip.isEncrypted) {
            if (password == null) throw ZipException("Passwort erforderlich")
            zip.setPassword(password.toCharArray())
        } else if (password != null) {
            // unverschluesselt wurde bereits im ersten Durchlauf probiert
            throw ZipException("Kein Passwort nötig")
        }
        if (excludes.isEmpty()) {
            zip.extractAll(destDir.absolutePath)
            return
        }
        for (header in zip.fileHeaders) {
            if (header.isDirectory) continue
            if (isExcluded(header.fileName, excludes)) continue
            safeChild(destDir, header.fileName)
            zip.extractFile(header, destDir.absolutePath)
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

    private fun extractSevenZip(archive: File, destDir: File, password: String?, excludes: List<String>) {
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
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                if (isExcluded(entry.name, excludes)) continue
                val out = safeChild(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = sevenZ.read(buffer)
                        if (read < 0) break
                        stream.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /**
     * RAR ueber das native 7-Zip-Binding: unterstuetzt RAR4 und RAR5,
     * jeweils inkl. Verschluesselung und Multivolume (.partN.rar).
     */
    private fun extractRar(archive: File, destDir: File, password: String?, excludes: List<String>) {
        ensureSevenZip()
        val openCallback = RarOpenCallback(archive, password)
        RandomAccessFile(archive, "r").use { raf ->
            val inArchive = SevenZip.openInArchive(
                null, RandomAccessFileInStream(raf), openCallback
            )
            try {
                for (item in inArchive.simpleInterface.archiveItems) {
                    val path = item.path ?: continue
                    if (isExcluded(path, excludes)) continue
                    val out = safeChild(destDir, path)
                    if (item.isFolder) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    val result = out.outputStream().use { stream ->
                        val sink = net.sf.sevenzipjbinding.ISequentialOutStream { data ->
                            stream.write(data)
                            data.size
                        }
                        if (password != null) item.extractSlow(sink, password)
                        else item.extractSlow(sink)
                    }
                    if (result != ExtractOperationResult.OK) {
                        throw IOException("RAR-Extraktion: $result")
                    }
                }
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

    /**
     * Gemeinsamer Basisname aller Teile eines Archivs, z.B.
     * "film.part2.rar" -> "film". Null, wenn kein Archiv.
     */
    fun archiveBase(fileName: String): String? {
        val lower = fileName.lowercase()
        Regex("""^(.*?)\.part\d+\.rar$""").find(lower)?.let { return it.groupValues[1] }
        Regex("""^(.*?)\.7z\.\d+$""").find(lower)?.let { return it.groupValues[1] }
        Regex("""^(.*?)\.(rar|zip|7z)$""").find(lower)?.let { return it.groupValues[1] }
        Regex("""^(.*?)\.[rz]\d{2}$""").find(lower)?.let { return it.groupValues[1] }
        return null
    }

    /** Erstes/primaeres Volume eines Archivs im Verzeichnis finden. */
    fun findPrimaryVolume(dir: File, base: String): File? =
        dir.listFiles()?.firstOrNull { file ->
            val lower = file.name.lowercase()
            archiveBase(file.name) == base && !isSecondaryVolume(file.name) &&
                (lower.endsWith(".rar") || lower.endsWith(".zip") ||
                    lower.endsWith(".7z") || lower.endsWith(".7z.001"))
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
