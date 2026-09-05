package com.jdandroid

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the stack trace of an uncaught exception to a file so it can be
 * shown after the next start.
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
