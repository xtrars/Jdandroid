package com.jdandroid.container

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * Liest Container-Dateien (DLC) sicher ein: mit fester Obergrenze, damit
 * eine versehentlich geteilte grosse Datei (Video, Archiv) nicht den ganzen
 * Speicher belegt.
 */
object ContainerFiles {

    /** DLC-Dateien sind einige Kilobyte gross; 2 MiB ist grosszuegig. */
    const val MAX_BYTES = 2L * 1024 * 1024

    class TooLargeException(size: Long) : IOException(
        ContainerTexts.t("service_dlc_file_too_large", size / 1024, MAX_BYTES / 1024)
    )

    /**
     * Liefert den Textinhalt oder wirft [TooLargeException] bzw. [IOException].
     * Die Groesse wird vorab ueber den ContentResolver geprueft und beim
     * Lesen zusaetzlich begrenzt (nicht jeder Provider meldet eine Groesse).
     */
    fun readText(resolver: ContentResolver, uri: Uri): String {
        querySize(resolver, uri)?.let { size ->
            if (size > MAX_BYTES) throw TooLargeException(size)
        }
        val input = resolver.openInputStream(uri) ?: throw IOException(ContainerTexts.t("service_dlc_file_unreadable"))
        input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw TooLargeException(total)
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index)
            else null
        }
    }.getOrNull()

    /**
     * Sieht der Inhalt wie ein DLC aus? Ein DLC ist reines Base64, mindestens
     * 88 Zeichen (der Schluesselteil) und endet wegen dessen Laenge immer auf "==".
     */
    fun looksLikeDlc(content: String): Boolean {
        val data = content.filterNot { it.isWhitespace() }
        if (data.length < 88 || !data.endsWith("==")) return false
        return data.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
        }
    }
}
