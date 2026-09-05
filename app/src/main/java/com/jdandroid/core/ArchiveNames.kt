package com.jdandroid.core

/**
 * Pure archive name logic: set base name, repair of names without dots and
 * detection of secondary volumes. Android-free so the data layer (migration,
 * archiveKey) can use it without knowing the engine.
 */
object ArchiveNames {

    private val knownExtensions = setOf(
        "rar", "zip", "7z", "mkv", "mp4", "avi", "mov", "wmv", "iso", "img", "bin",
        "exe", "msi", "apk", "pdf", "epub", "mp3", "flac", "m4a", "txt", "nfo",
        "srt", "sfv", "par2", "tar", "gz", "bz2", "xz", "ts", "m2ts", "wav", "ogg"
    )

    /**
     * Value for downloads.archiveKey: the archive base name, also for names
     * without dots ("film part2 rar" -> "film"); null for non-archives.
     */
    fun archiveKey(fileName: String?): String? = fileName?.let { archiveBase(repairName(it)) }

    /**
     * Repairs names where the hoster replaced dots with spaces ("Download
     * name part1 rar" for "name.part1.rar"); without an extension neither the
     * archive nor its parts are recognised. Only names without a dot whose
     * last word is a known extension; a "Download " prefix is dropped.
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

    /** Common base name of all parts, e.g. "film.part2.rar" -> "film"; null if not an archive. */
    fun archiveBase(fileName: String): String? {
        val lower = fileName.lowercase()
        Regex("""^(.*?)\.part\d+\.rar$""").find(lower)?.let { return it.groupValues[1] }
        Regex("""^(.*?)\.7z\.\d+$""").find(lower)?.let { return it.groupValues[1] }
        Regex("""^(.*?)\.(rar|zip|7z)$""").find(lower)?.let { return it.groupValues[1] }
        Regex("""^(.*?)\.[rz]\d{2}$""").find(lower)?.let { return it.groupValues[1] }
        return null
    }

    /** Secondary volumes (part2, .r00, .z01 ...) are extracted via the first part, never on their own. */
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
