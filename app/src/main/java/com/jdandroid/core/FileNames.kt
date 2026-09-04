package com.jdandroid.core

import okhttp3.HttpUrl
import java.io.File

/**
 * Dateinamen aus Server-Antworten ableiten und dateisystemtauglich machen.
 * Einzige Stelle fuer Sanitizer, Content-Disposition-Parser und Laengenlimit -
 * vorher lagen drei abweichende Kopien in Engine, Linkpruefung und Hoster.
 */
internal object FileNames {
    const val FALLBACK = "download.bin"

    /** Auf Android/ext4 verboten bzw. auf FAT/SAF problematisch. */
    private val forbidden = Regex("""[/\\:*?"<>|]""")

    private val encodedName = Regex("""filename\*=(?:[Uu][Tt][Ff]-8)?'[^']*'([^;]+)""")
    private val plainName = Regex("""filename="([^"]*)"|filename=([^;]+)""")

    /** Verbotene Zeichen ersetzen, Leerraum und fuehrende Punkte entfernen; leer -> null. */
    fun clean(name: String): String? =
        name.replace(forbidden, "_").trim().trimStart('.').ifBlank { null }

    /** Server-gelieferte Namen bereinigen (Pfad-Traversal, verbotene Zeichen, Laenge). */
    fun sanitize(name: String, maxBytes: Int = MAX_BYTES): String =
        limitLength(clean(name) ?: FALLBACK, maxBytes)

    /** Dateisysteme erlauben 255 Byte; Endung erhalten, Basis kuerzen (auch bei Multibyte). */
    fun limitLength(name: String, maxBytes: Int = MAX_BYTES): String {
        if (name.toByteArray().size <= maxBytes) return name
        val ext = name.substringAfterLast('.', "").take(10)
        var base = name.substringBeforeLast('.')
        while (base.isNotEmpty() && (base + "." + ext).toByteArray().size > maxBytes) base = base.dropLast(1)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /**
     * Dateiname aus Content-Disposition, bereinigt; null ohne verwertbaren Namen.
     * RFC 5987: nur filename*= ist URL-kodiert und hat Vorrang; ein rohes
     * filename="C++.zip" darf nicht dekodiert werden ("C  .zip", "100%.rar" wuerde werfen).
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
     * Name fuer einen Download: Content-Disposition, sonst letztes Pfadsegment
     * der endgueltigen Adresse (nach Weiterleitungen; Segmente sind bereits
     * dekodiert, "My%20File.rar" -> "My File.rar"), sonst [FALLBACK].
     */
    fun fromResponse(contentDisposition: String?, url: HttpUrl): String =
        fromDisposition(contentDisposition)
            ?: sanitize(url.pathSegments.lastOrNull { it.isNotBlank() } ?: "")

    /**
     * Ist [candidate] der bessere Dateiname? Ja, wenn bisher keiner bekannt
     * ist, der bisherige keine Endung traegt oder erst der neue ein Archiv
     * erkennen laesst.
     */
    fun preferName(current: String?, candidate: String): Boolean {
        if (current == null) return true
        if (current == candidate) return false
        val currentExt = current.substringAfterLast('.', "")
        val hasExt = currentExt.isNotEmpty() && currentExt.length <= 10 && !currentExt.contains(' ')
        if (!hasExt) return true
        return ArchiveNames.archiveBase(current) == null && ArchiveNames.archiveBase(candidate) != null
    }

    /**
     * Liefert einen freien Dateinamen: "film.mkv" -> "film (2).mkv".
     * Vorher wurde eine bereits vorhandene Datei kommentarlos ueberschrieben.
     */
    fun uniqueFile(dir: File, fileName: String): File {
        val candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var index = 2
        while (index < 1000) {
            val next = File(dir, "$base ($index)$suffix")
            if (!next.exists()) return next
            index++
        }
        return File(dir, "$base (${System.currentTimeMillis()})$suffix")
    }

    private const val MAX_BYTES = 200
}
