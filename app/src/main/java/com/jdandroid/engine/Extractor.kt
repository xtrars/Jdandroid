package com.jdandroid.engine

import com.jdandroid.core.ArchiveNames
import com.jdandroid.core.FileNames
import com.jdandroid.core.Texts
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.UnzipParameters
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * Extracts ZIP, 7z and RAR archives. Passwords are tried in list order,
 * starting without a password.
 */
object Extractor {

    private val archiveExtensions = listOf(".zip", ".7z", ".rar")

    /** Progress is reported at most every 4 MiB. */
    private const val PROGRESS_STEP = 4L * 1024 * 1024

    /** Windows attribute flags as stored by 7-Zip and RAR. */
    private const val ATTR_REPARSE_POINT = 0x400
    private const val ATTR_UNIX_EXTENSION = 0x8000

    @Volatile
    private var sevenZipReady = false

    /**
     * Loads the native 7-Zip library. 7-Zip-JBinding's auto-initialisation
     * looks for a platform JAR that does not exist in an APK; the .so ships in
     * the APK's lib directory and must be loaded before initLoadedLibraries().
     */
    @Synchronized
    private fun ensureSevenZip() {
        if (sevenZipReady) return
        try {
            System.loadLibrary("7-Zip-JBinding")
            SevenZip.initLoadedLibraries()
            sevenZipReady = true
        } catch (e: Throwable) {
            throw IOException(Texts.t("engine_seven_zip_unavailable", e.message ?: e.javaClass.simpleName))
        }
    }

    /** See [ArchiveNames.repairName]. */
    fun repairName(name: String): String = ArchiveNames.repairName(name)

    /** Archive format from the magic bytes: "rar", "zip", "7z" or null. */
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

    /** See [ArchiveNames.isSecondaryVolume]. */
    fun isSecondaryVolume(fileName: String): Boolean = ArchiveNames.isSecondaryVolume(fileName)

    /**
     * Exclude patterns as in JDownloader ("*.nfo", "*sample*"): * matches any
     * run of characters, ? one character; matched case-insensitively against
     * the file name and the full path inside the archive.
     */
    fun isExcluded(entryPath: String, patterns: List<String>): Boolean =
        ExcludeFilter(patterns).matches(entryPath)

    /** Exclude patterns compiled once per extraction. */
    internal class ExcludeFilter(patterns: List<String>) {
        private val regexes: List<Regex> = patterns.mapNotNull { pattern ->
            val p = pattern.trim()
            if (p.isEmpty()) return@mapNotNull null
            buildString {
                for (c in p) {
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
            }.toRegex(RegexOption.IGNORE_CASE)
        }

        fun matches(entryPath: String): Boolean {
            if (regexes.isEmpty()) return false
            val path = entryPath.replace('\\', '/').trimStart('/')
            val name = path.substringAfterLast('/')
            return regexes.any { it.matches(name) || it.matches(path) }
        }
    }

    /** Failure that another password from the list may fix. */
    internal class PasswordException(message: String) : IOException(message)

    /** Extracted bytes and total bytes (0 = unknown). */
    fun interface ProgressListener {
        fun onProgress(done: Long, total: Long)
    }

    /**
     * Extracts [archive] into [destDir], trying no password first and then
     * every entry of [passwords]. Returns the password used (null for an
     * unencrypted archive); throws [IOException] if none fits.
     */
    fun extract(
        archive: File,
        destDir: File,
        passwords: List<String>,
        excludes: List<String> = emptyList(),
        flat: Boolean = false,
        progress: ProgressListener? = null
    ): String? {
        destDir.mkdirs()
        // Each attempt extracts into its own work directory: destDir is the package
        // folder and may hold files of other archives that a failed attempt must
        // not delete. On success the content moves to destDir (same partition).
        val workDir = File(destDir, ".extract-" + (archiveBase(archive.name) ?: archive.name))
        val candidates = listOf<String?>(null) + passwords.filter { it.isNotBlank() }
        val filter = ExcludeFilter(excludes)
        var lastError: Exception? = null
        try {
            for (password in candidates) {
                FileTrees.deleteTree(workDir)
                workDir.mkdirs()
                val target = Destination(workDir)
                try {
                    val lower = archive.name.lowercase()
                    when {
                        lower.endsWith(".zip") -> extractZip(archive, target, password, filter, progress, flat)
                        lower.endsWith(".7z") || Regex("""\.7z\.\d+$""").containsMatchIn(lower) ->
                            extractSevenZip(archive, target, password, filter, progress, flat)
                        lower.endsWith(".rar") -> extractRar(archive, target, password, filter, progress, flat)
                        else -> throw IOException(Texts.t("engine_unknown_archive_format", archive.name))
                    }
                    workDir.listFiles()?.forEach { moveInto(it, File(destDir, it.name)) }
                    return password
                } catch (e: PasswordException) {
                    lastError = e
                } catch (e: Exception) {
                    throw IOException(Texts.t("engine_extract_error", e.message ?: e.javaClass.simpleName), e)
                }
            }
        } finally {
            FileTrees.deleteTree(workDir)
        }
        throw IOException(
            Texts.t("engine_extract_failed", lastError?.message ?: lastError?.javaClass?.simpleName ?: "")
        )
    }

    /**
     * Moves [src] to [dst], merging existing directories and suffixing
     * existing file names with "(2)", "(3)", ...; falls back to copying.
     */
    private fun moveInto(src: File, dst: File) {
        if (src.isDirectory && dst.isDirectory) {
            src.listFiles()?.forEach { moveInto(it, File(dst, it.name)) }
            src.delete()
            return
        }
        val free = if (dst.exists()) FileNames.uniqueFile(dst.parentFile!!, dst.name) else dst
        if (!src.renameTo(free)) {
            if (!src.copyRecursively(free, overwrite = true)) {
                throw IOException(Texts.t("engine_move_failed", src.name, free.parent ?: ""))
            }
            FileTrees.deleteTree(src)
        }
    }

    private fun extractZip(
        archive: File, target: Destination, password: String?, filter: ExcludeFilter,
        progress: ProgressListener?, flat: Boolean
    ) {
        val zip = ZipFile(archive)
        val encrypted = zip.isEncrypted
        if (encrypted) {
            if (password == null) throw PasswordException(Texts.t("engine_password_required"))
            zip.setPassword(password.toCharArray())
        }
        // Per file instead of extractAll so excludes and progress work
        val headers = zip.fileHeaders.filter { !it.isDirectory && !isLink(it) && !filter.matches(it.fileName) }
        val total = headers.sumOf { it.uncompressedSize.coerceAtLeast(0) }
        var done = 0L
        val params = UnzipParameters().apply { isExtractSymbolicLinks = false }
        progress?.onProgress(0, total)
        for (header in headers) {
            val out = target.fileFor(header.fileName, flat)
            try {
                if (flat) zip.extractFile(header, target.dir.absolutePath, out.name, params)
                else zip.extractFile(header, target.dir.absolutePath, params)
            } catch (e: ZipException) {
                val passwordError = e.type == ZipException.Type.WRONG_PASSWORD ||
                    e.type == ZipException.Type.CHECKSUM_MISMATCH
                if (encrypted && passwordError) throw PasswordException(Texts.t("engine_wrong_password"))
                throw e
            }
            done += header.uncompressedSize.coerceAtLeast(0)
            progress?.onProgress(done, total)
        }
    }

    /** Unix mode lives in the upper two bytes of the ZIP external attributes. */
    private fun isLink(header: FileHeader): Boolean {
        val attrs = header.externalFileAttributes ?: return false
        if (attrs.size < 4) return false
        return isLinkMode(((attrs[3].toInt() and 0xFF) shl 8) or (attrs[2].toInt() and 0xFF))
    }

    /** S_IFLNK in a Unix mode word. */
    internal fun isLinkMode(unixMode: Int): Boolean = unixMode and 0xF000 == 0xA000

    /** 7-Zip/RAR attributes: Unix mode in the high 16 bits when 0x8000 is set, or a Windows reparse point. */
    internal fun isLinkAttributes(attributes: Int): Boolean =
        attributes and ATTR_REPARSE_POINT != 0 ||
            (attributes and ATTR_UNIX_EXTENSION != 0 && isLinkMode(attributes ushr 16))

    private fun isLink(entry: SevenZArchiveEntry): Boolean =
        entry.hasWindowsAttributes && isLinkAttributes(entry.windowsAttributes)

    /** All volumes of a multipart 7z (.7z.001, .7z.002, ...) in order. */
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

    /**
     * commons-compress reports a wrong 7z password only as corrupt data. A
     * password is passed only after the archive asked for one, so decoder
     * failures with a password count as password failures.
     */
    private inline fun <T> decoding7z(password: String?, block: () -> T): T = try {
        block()
    } catch (e: PasswordRequiredException) {
        throw PasswordException(Texts.t("engine_password_required"))
    } catch (e: Exception) {
        if (password == null) throw e
        throw PasswordException(Texts.t("engine_wrong_password"))
    }

    private fun extractSevenZip(
        archive: File, target: Destination, password: String?, filter: ExcludeFilter,
        progress: ProgressListener?, flat: Boolean
    ) {
        val volumes = sevenZVolumes(archive)
        val builder = SevenZFile.builder()
        if (volumes.size > 1) {
            builder.setSeekableByteChannel(MultiReadOnlySeekableByteChannel.forFiles(*volumes.toTypedArray()))
        } else {
            builder.setFile(archive)
        }
        if (password != null) builder.setPassword(password.toCharArray())
        decoding7z(password) { builder.get() }.use { sevenZ ->
            val entries = sevenZ.entries.toList()
            val skip = BooleanArray(entries.size) { i ->
                val entry = entries[i]
                filter.matches(entry.name) || (!entry.isDirectory && isLink(entry))
            }
            val total = entries.indices
                .filter { !skip[it] && !entries[it].isDirectory }
                .sumOf { entries[it].size.coerceAtLeast(0) }
            var done = 0L
            var lastReport = 0L
            progress?.onProgress(0, total)
            var index = 0
            while (true) {
                val entry = decoding7z(password) { sevenZ.nextEntry } ?: break
                val i = index++
                if (i < skip.size && skip[i]) continue
                if (entry.isDirectory) {
                    if (!flat) target.child(entry.name).mkdirs()
                    continue
                }
                val out = target.fileFor(entry.name, flat)
                out.parentFile?.mkdirs()
                out.outputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = decoding7z(password) { sevenZ.read(buffer) }
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
     * Encrypted RAR headers: RAR4 main header flag MHD_PASSWORD (0x0080),
     * RAR5 first block of type 4 (archive encryption header). Only such
     * archives fail to open because of the password.
     */
    internal fun rarHeadersEncrypted(archive: File): Boolean {
        val head = ByteArray(32)
        val n = runCatching { archive.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n < 12) return false
        fun at(i: Int) = head[i].toInt() and 0xFF
        val rar = at(0) == 'R'.code && at(1) == 'a'.code && at(2) == 'r'.code && at(3) == '!'.code &&
            at(4) == 0x1A && at(5) == 0x07
        if (!rar) return false
        return when (at(6)) {
            0x00 -> at(9) == 0x73 && at(10) and 0x80 != 0
            0x01 -> {
                var i = 12
                while (i < n && at(i) and 0x80 != 0) i++
                i++
                i < n && at(i) == 4
            }
            else -> false
        }
    }

    /** RAR4 and RAR5 via the native 7-Zip binding, including encryption and multivolume sets. */
    private fun extractRar(
        archive: File, target: Destination, password: String?, filter: ExcludeFilter,
        progress: ProgressListener?, flat: Boolean
    ) {
        ensureSevenZip()
        val openCallback = RarOpenCallback(archive, password)
        RandomAccessFile(archive, "r").use { raf ->
            val inArchive = try {
                SevenZip.openInArchive(null, RandomAccessFileInStream(raf), openCallback)
            } catch (e: SevenZipException) {
                openCallback.close()
                if (!rarHeadersEncrypted(archive)) throw e
                throw PasswordException(
                    Texts.t(if (password == null) "engine_password_required" else "engine_wrong_password")
                )
            }
            try {
                val files = LinkedHashMap<Int, String>()
                var total = 0L
                for (i in 0 until inArchive.numberOfItems) {
                    val path = inArchive.getProperty(i, PropID.PATH) as? String ?: continue
                    if (filter.matches(path)) continue
                    if (inArchive.getProperty(i, PropID.IS_FOLDER) == true) {
                        if (!flat) target.child(path).mkdirs()
                        continue
                    }
                    val attributes = inArchive.getProperty(i, PropID.ATTRIBUTES) as? Int ?: 0
                    if (isLinkAttributes(attributes)) continue
                    files[i] = path
                    total += (inArchive.getProperty(i, PropID.SIZE) as? Long ?: 0L).coerceAtLeast(0)
                }
                progress?.onProgress(0, total)
                val callback = RarExtractCallback(inArchive, files, target, flat, password, progress, total)
                try {
                    inArchive.extract(files.keys.toIntArray(), false, callback)
                } catch (e: SevenZipException) {
                    throw callback.failure ?: e
                } finally {
                    callback.close()
                }
                callback.failure?.let { throw it }
                progress?.onProgress(callback.done, total)
            } finally {
                runCatching { inArchive.close() }
                openCallback.close()
            }
        }
    }

    /** Supplies further volumes of a multipart RAR and the password while opening. */
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
     * Writes all requested items in one native pass (solid archives are
     * decompressed once). Failures are kept in [failure] because exceptions
     * thrown inside a callback surface only as a generic [SevenZipException].
     */
    private class RarExtractCallback(
        private val inArchive: IInArchive,
        private val files: Map<Int, String>,
        private val target: Destination,
        private val flat: Boolean,
        private val password: String?,
        private val progress: ProgressListener?,
        private val total: Long
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {

        var failure: IOException? = null
            private set
        var done = 0L
            private set
        private var lastReport = 0L
        private var current: OutputStream? = null
        private var currentEncrypted = false

        override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
            currentEncrypted = inArchive.getProperty(index, PropID.ENCRYPTED) == true
            if (extractAskMode != ExtractAskMode.EXTRACT) return null
            val path = files[index] ?: return null
            val stream = try {
                val out = target.fileFor(path, flat)
                out.parentFile?.mkdirs()
                out.outputStream()
            } catch (e: IOException) {
                throw fail(e)
            }
            current = stream
            return ISequentialOutStream { data ->
                try {
                    stream.write(data)
                } catch (e: IOException) {
                    throw fail(e)
                }
                done += data.size
                if (done - lastReport >= PROGRESS_STEP) {
                    lastReport = done
                    progress?.onProgress(done, total)
                }
                data.size
            }
        }

        override fun prepareOperation(extractAskMode: ExtractAskMode) {}

        override fun setOperationResult(result: ExtractOperationResult) {
            close()
            if (result == ExtractOperationResult.OK) return
            val message = Texts.t("engine_rar_extraction_result", result.toString())
            throw fail(if (currentEncrypted) PasswordException(message) else IOException(message))
        }

        override fun setTotal(total: Long) {}
        override fun setCompleted(complete: Long) {}

        override fun cryptoGetTextPassword(): String = password ?: ""

        private fun fail(e: IOException): SevenZipException {
            if (failure == null) failure = e
            return SevenZipException(e.message, e)
        }

        fun close() {
            current?.let { runCatching { it.close() } }
            current = null
        }
    }

    /** See [ArchiveNames.archiveBase]. */
    fun archiveBase(fileName: String): String? = ArchiveNames.archiveBase(fileName)

    /** First volume of the archive [base] in [dir], or null. */
    fun findPrimaryVolume(dir: File, base: String): File? =
        dir.listFiles()?.firstOrNull { file ->
            val lower = file.name.lowercase()
            archiveBase(file.name) == base && !isSecondaryVolume(file.name) &&
                (lower.endsWith(".rar") || lower.endsWith(".zip") ||
                    lower.endsWith(".7z") || lower.endsWith(".7z.001"))
        }

    /**
     * Target directory of one extraction attempt. Entry paths are checked
     * lexically against the directory's canonical path.
     */
    internal class Destination(dir: File) {
        val dir: File = dir.canonicalFile
        private val root: Path = this.dir.toPath()
        private val used = HashSet<String>()

        /** Zip-slip guard: the target must stay inside the directory. */
        fun child(entryPath: String): File {
            val target = File(dir, entryPath)
            val normalized = target.toPath().normalize()
            if (normalized == root || !normalized.startsWith(root)) {
                throw IOException(Texts.t("engine_invalid_archive_path", entryPath))
            }
            return target
        }

        /**
         * Target file for an entry. Flat mode ignores the archive's folders;
         * equal names from different folders get "(2)", "(3)", ... appended.
         */
        fun fileFor(entryPath: String, flat: Boolean): File {
            if (!flat) return child(entryPath)
            val name = entryPath.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { "datei" }
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            var candidate = name
            var n = 2
            while (candidate.lowercase() in used || File(dir, candidate).exists()) {
                candidate = "$base ($n)$ext"
                n++
            }
            used += candidate.lowercase()
            return child(candidate)
        }
    }
}
