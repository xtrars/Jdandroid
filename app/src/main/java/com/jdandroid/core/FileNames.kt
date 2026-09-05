package com.jdandroid.core

import okhttp3.HttpUrl
import java.io.File

/**
 * Derives file names from server responses and makes them safe for the file
 * system: sanitizer, Content-Disposition parser and length limit.
 */
internal object FileNames {
    const val FALLBACK = "download.bin"

    /** Forbidden on ext4 or problematic on FAT/SAF. */
    private val forbidden = Regex("""[/\\:*?"<>|]""")

    private val encodedName = Regex("""filename\*=(?:[Uu][Tt][Ff]-8)?'[^']*'([^;]+)""")
    private val plainName = Regex("""filename="([^"]*)"|filename=([^;]+)""")

    /** Replaces forbidden characters, trims whitespace and leading dots; empty -> null. */
    fun clean(name: String): String? =
        name.replace(forbidden, "_").trim().trimStart('.').ifBlank { null }

    /** Cleans a server-supplied name (path traversal, forbidden characters, length). */
    fun sanitize(name: String, maxBytes: Int = MAX_BYTES): String =
        limitLength(clean(name) ?: FALLBACK, maxBytes)

    /** File systems allow 255 bytes; keeps the extension and shortens the base (multibyte-safe). */
    fun limitLength(name: String, maxBytes: Int = MAX_BYTES): String {
        if (name.toByteArray().size <= maxBytes) return name
        val ext = name.substringAfterLast('.', "").take(10)
        var base = name.substringBeforeLast('.')
        while (base.isNotEmpty() && (base + "." + ext).toByteArray().size > maxBytes) base = base.dropLast(1)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /**
     * Sanitized name from Content-Disposition, null without a usable one.
     * RFC 5987: only filename*= is URL-encoded and takes precedence; a plain
     * filename="C++.zip" must not be decoded ("C  .zip"; "100%.rar" would throw).
     */
    fun fromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        encodedName.find(cd)?.groupValues?.get(1)?.let { enc ->
            runCatching { java.net.URLDecoder.decode(enc.trim().replace("+", "%2B"), "UTF-8") }
                .getOrNull()?.let { clean(it) }?.let { return sanitize(it) }
        }
        plainName.find(cd)?.let { m ->
            val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            clean(raw)?.let { return sanitize(it) }
        }
        return null
    }

    /**
     * Content-Disposition, else the last path segment of the final URL
     * (segments are already decoded), else [FALLBACK].
     */
    fun fromResponse(contentDisposition: String?, url: HttpUrl): String =
        fromDisposition(contentDisposition)
            ?: sanitize(url.pathSegments.lastOrNull { it.isNotBlank() } ?: "")

    /**
     * True when [candidate] should replace [current]: no current name, current
     * has no extension, or only the candidate identifies an archive.
     */
    fun preferName(current: String?, candidate: String): Boolean {
        if (current == null) return true
        if (current == candidate) return false
        val currentExt = current.substringAfterLast('.', "")
        val hasExt = currentExt.isNotEmpty() && currentExt.length <= 10 && !currentExt.contains(' ')
        if (!hasExt) return true
        return ArchiveNames.archiveBase(current) == null && ArchiveNames.archiveBase(candidate) != null
    }

    /** Free file in [dir]: "film.mkv" -> "film (2).mkv". */
    fun uniqueFile(dir: File, fileName: String): File =
        File(dir, uniqueName(fileName) { File(dir, it).exists() })

    /** Free name for [fileName] where [isTaken] is true for names already in use: "film.mkv" -> "film (2).mkv". */
    fun uniqueName(fileName: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(fileName)) return fileName
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        for (index in 2 until 1000) {
            val next = "$base ($index)$suffix"
            if (!isTaken(next)) return next
        }
        return "$base (${System.currentTimeMillis()})$suffix"
    }

    private const val MAX_BYTES = 200
}
