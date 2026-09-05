package com.jdandroid.core

/**
 * Thin wrapper around android.util.Log. In JVM unit tests the Android class
 * is a throwing stub; catching that keeps classes like the Click'n'Load
 * server testable without an Android runtime.
 */
object AppLog {
    fun w(tag: String, message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) android.util.Log.w(tag, message, error) else android.util.Log.w(tag, message)
        }
    }
}
