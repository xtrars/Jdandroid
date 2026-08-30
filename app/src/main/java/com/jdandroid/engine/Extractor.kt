package com.jdandroid.engine

import com.github.junrar.Junrar
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException

/**
 * Entpackt Archive nach dem Download. Passwoerter werden wie im JDownloader
 * der Reihe nach aus der Passwortliste probiert (zuerst ohne Passwort).
 */
object Extractor {

    private val archiveExtensions = listOf(".zip", ".7z", ".rar")

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
                when {
                    archive.name.lowercase().endsWith(".zip") -> extractZip(archive, destDir, password)
                    archive.name.lowercase().endsWith(".7z") -> extractSevenZip(archive, destDir, password)
                    archive.name.lowercase().endsWith(".rar") -> extractRar(archive, destDir, password)
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

    private fun extractSevenZip(archive: File, destDir: File, password: String?) {
        val builder = SevenZFile.builder().setFile(archive)
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

    private fun extractRar(archive: File, destDir: File, password: String?) {
        if (password != null) {
            Junrar.extract(archive, destDir, password)
        } else {
            Junrar.extract(archive, destDir)
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
