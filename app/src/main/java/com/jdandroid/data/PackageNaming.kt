package com.jdandroid.data

import com.jdandroid.core.Texts
import java.text.DateFormat
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
        val pkg = db.packageDao().byId(id) ?: return
        if (!pkg.autoNamed) return
        val name = refinedName(pkg, db.downloadDao().byPackage(id).mapNotNull { it.fileName }) ?: return
        db.packageDao().refineAutoName(id, name)
    }

    /**
     * New name for [pkg] derived from [fileNames], or null when nothing should be
     * written (manually named, no common part, or the name is already current).
     */
    fun refinedName(pkg: DownloadPackage, fileNames: List<String>): String? {
        if (!pkg.autoNamed) return null
        val name = commonName(fileNames) ?: return null
        return name.takeIf { it != pkg.name }
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
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date())
        return Texts.t("engine_package_from_date", stamp)
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
