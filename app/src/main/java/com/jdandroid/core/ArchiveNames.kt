package com.jdandroid.core

/**
 * Reine Namenslogik fuer Archive: Basisname eines Sets, Reparatur von Namen
 * ohne Punkte und Erkennung weiterer Volumes. Ohne Android-Abhaengigkeit,
 * damit die Datenschicht (Migration, archiveKey) sie nutzen kann, ohne die
 * Engine zu kennen.
 */
object ArchiveNames {

    private val knownExtensions = setOf(
        "rar", "zip", "7z", "mkv", "mp4", "avi", "mov", "wmv", "iso", "img", "bin",
        "exe", "msi", "apk", "pdf", "epub", "mp3", "flac", "m4a", "txt", "nfo",
        "srt", "sfv", "par2", "tar", "gz", "bz2", "xz", "ts", "m2ts", "wav", "ogg"
    )

    /**
     * Schluessel fuer die Spalte downloads.archiveKey: der Basisname des
     * Archivs, auch bei Namen ohne Punkte ("film part2 rar" -> "film").
     * Null ohne Namen oder wenn der Name kein Archiv bezeichnet.
     */
    fun archiveKey(fileName: String?): String? = fileName?.let { archiveBase(repairName(it)) }

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

    /**
     * Weitere Teile eines Multipart-Archivs (part2, .r00, .z01 ...) nicht selbst
     * entpacken - das erledigt der erste Teil mit.
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
}
