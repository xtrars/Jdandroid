package com.jdandroid

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt den Stacktrace eines Absturzes in eine Datei, damit er nach dem
 * Neustart in den Einstellungen sichtbar ist. Ohne das bleibt bei einem
 * Absturz auf dem Geraet nur Raten.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
                    .format(Date())
                file(appContext).writeText(
                    "Zeitpunkt: $stamp\nThread: ${thread.name}\n\n$writer"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun lastCrash(context: Context): String? =
        file(context).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
