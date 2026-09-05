package com.jdandroid.core

/**
 * Schmale Huelle um android.util.Log. In JVM-Unit-Tests ist die Android-
 * Klasse nur ein Stub, der bei jedem Aufruf wirft; hier wird das abgefangen,
 * sodass Klassen wie der Click'n'Load-Server ohne Android-Laufzeit testbar
 * bleiben.
 */
object AppLog {
    fun w(tag: String, message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) android.util.Log.w(tag, message, error) else android.util.Log.w(tag, message)
        }
    }
}
