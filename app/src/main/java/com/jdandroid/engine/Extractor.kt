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
    fun extract(archive: File, destDir: File, passwords: List<String>): String? {
        destDir.mkdirs()
        val candidates = listOf<String?>(null) + passwords.filter { it.isNotBlank() }
        var lastError: Exception? = null
        for (password in candidates) {
            try {
                val lower = archive.name.lowercase()
                when {
                    lower.endsWith(".zip") -> extractZip(archive, destDir, password)
                    lower.endsWith(".7z") || Regex("""\.7z\.\d+$""").containsMatchIn(lower) ->
                        extractSevenZip(archive, destDir, password)
                    lower.endsWith(".rar") -> extractRar(archive, destDir, password)
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

    private fun extractZip(archive: File, destDir: File, password: String?) {
        val zip = ZipFile(archive)
        if (zip.isEncrypted) {
            if (password == null) throw ZipException("Passwort erforderlich")
            zip.setPassword(password.toCharArray())
        } else if (password != null) {
            // unverschluesselt wurde bereits im ersten Durchlauf probiert
            throw ZipException("Kein Passwort nötig")
        }
        zip.extractAll(destDir.absolutePath)
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

    private fun extractSevenZip(archive: File, destDir: File, password: String?) {
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
    private fun extractRar(archive: File, destDir: File, password: String?) {
        ensureSevenZip()
        val openCallback = RarOpenCallback(archive, password)
        RandomAccessFile(archive, "r").use { raf ->
            val inArchive = SevenZip.openInArchive(
                null, RandomAccessFileInStream(raf), openCallback
            )
            try {
                for (item in inArchive.simpleInterface.archiveItems) {
                    val path = item.path ?: continue
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
