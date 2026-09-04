package com.jdandroid.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benennung von Paketen wie im JDownloader: aus dem gemeinsamen Namensteil
 * der enthaltenen Dateien. Aus "film.part1.rar" und "film.part2.rar" wird
 * also das Paket "film".
 */
object PackageNaming {

    /**
     * Sobald Dateinamen bekannt sind, wird ein automatisch benanntes Paket
     * nach dem gemeinsamen Namensteil benannt - wie im JDownloader.
     */
    suspend fun refineAutoName(db: AppDatabase, packageId: Long?) {
        val id = packageId ?: return
        val names = db.downloadDao().byPackage(id).mapNotNull { it.fileName }
        val name = commonName(names) ?: return
        db.packageDao().refineAutoName(id, name)
    }

    /** Gemeinsamer Namensteil mehrerer Dateinamen, oder null wenn zu kurz. */
    fun commonName(names: List<String>): String? {
        // Archiv-Endungen zuerst entfernen: sonst bliebe vom gemeinsamen
        // Praefix von "film.part1.rar" und "film.part2.rar" das Fragment
        // "film.part" uebrig.
        val cleaned = names.filter { it.isNotBlank() }.map { stripArchiveSuffix(it) }
        if (cleaned.isEmpty()) return null
        if (cleaned.size == 1) return tidy(cleaned.first())

        var prefix = cleaned.first()
        for (name in cleaned.drop(1)) {
            var i = 0
            while (i < prefix.length && i < name.length && prefix[i].equals(name[i], true)) i++
            prefix = prefix.substring(0, i)
            if (prefix.isEmpty()) break
        }
        return tidy(prefix)
    }

    /** Aus Links einen Vorschlag ableiten (viele Links enthalten den Dateinamen). */
    fun suggestFromUrls(urls: List<String>): String {
        val names = urls.mapNotNull { url ->
            url.substringBefore('?').substringAfterLast('/')
                .takeIf { it.contains('.') && it.length > 3 }
        }
        commonName(names)?.let { return it }
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        return "Paket vom $stamp"
    }

    /** Entfernt Archiv-Endungen: "film.part1.rar" -> "film". */
    private fun stripArchiveSuffix(name: String): String =
        name.replace(Regex("\\.part\\d+\\.rar$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.7z\\.\\d+$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.(rar|zip|7z|[rz]\\d{2})$", RegexOption.IGNORE_CASE), "")
            .trim()

    /** Trennzeichen und Reste wie "part" am Ende entfernen. */
    private fun tidy(value: String): String? {
        val trimmed = value.trim()
            .replace(Regex("[._\\- ]*part\\d*$", RegexOption.IGNORE_CASE), "")
            .trimEnd('.', '-', '_', ' ', '(', '[')
        return trimmed.ifBlank { null }?.takeIf { it.length >= 3 }
    }
}
