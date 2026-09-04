package com.jdandroid

import android.content.Context
import java.io.File

/**
 * Kleine Diagnose-Ablage fuer Faelle, die sich ohne Konto nicht nachstellen
 * lassen (z.B. der Aufbau der ddownload-Kontoseite): Hoster legen hier einen
 * bereinigten Textausschnitt ab, die Einstellungen zeigen ihn zum Kopieren.
 * Es werden nie Zugangsdaten oder Cookies gespeichert, nur sichtbarer Seitentext.
 */
object Diagnostics {

    /** Senke fuer Hoster ohne Context; wird von JdApp gesetzt. */
    @Volatile
    var sink: ((key: String, title: String, text: String) -> Unit)? = null

    private const val DIR = "diagnostics"
    private const val MAX_CHARS = 4000

    fun save(context: Context, key: String, title: String, text: String) {
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            File(dir, "$key.txt").writeText(title + "\n" + text.take(MAX_CHARS))
        }
    }

    /** Alle Eintraege als (Titel, Text). */
    fun all(context: Context): List<Pair<String, String>> {
        val dir = File(context.filesDir, DIR)
        return dir.listFiles()?.sortedBy { it.name }?.mapNotNull { file ->
            runCatching {
                val lines = file.readText().split('\n', limit = 2)
                lines[0] to (lines.getOrNull(1) ?: "")
            }.getOrNull()
        }.orEmpty()
    }

    fun clear(context: Context) {
        File(context.filesDir, DIR).deleteRecursively()
    }
}
