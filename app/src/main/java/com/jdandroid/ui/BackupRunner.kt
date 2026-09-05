package com.jdandroid.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jdandroid.JdApp
import com.jdandroid.data.RestoreResult
import com.jdandroid.data.SettingsBackupFormat
import com.jdandroid.data.SettingsBackups
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Writes and reads backup files outside the composition, so a rotation
 * neither cancels a running transfer nor drops its result.
 */
internal class BackupRunner(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Result of the last finished action; null while none finished or a new one runs. */
    private val _outcome = MutableStateFlow<BackupOutcome?>(null)
    val outcome: StateFlow<BackupOutcome?> = _outcome

    /** Chosen file awaiting the user's confirmation to restore. */
    private val _pendingRestore = MutableStateFlow<Uri?>(null)
    val pendingRestore: StateFlow<Uri?> = _pendingRestore

    fun save(context: Context, uri: Uri, includeCredentials: Boolean) = run(context) { app ->
        val text = SettingsBackupFormat.write(SettingsBackups.collect(app, includeCredentials))
        writeText(app, uri, text)
        BackupOutcome.Saved(includeCredentials)
    }

    fun askRestore(uri: Uri) {
        _pendingRestore.value = uri
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore(context: Context) {
        val uri = _pendingRestore.value ?: return
        _pendingRestore.value = null
        run(context) { app ->
            val backup = SettingsBackupFormat.read(readText(app, uri))
            BackupOutcome.Restored(SettingsBackups.restore(app, backup))
        }
    }

    private fun run(context: Context, action: suspend (JdApp) -> BackupOutcome) {
        if (!_busy.compareAndSet(expect = false, update = true)) return
        _outcome.value = null
        val app = context.applicationContext as JdApp
        scope.launch {
            _outcome.value = withContext(io) {
                runCatching { action(app) }.getOrElse { BackupOutcome.Failed(it) }
            }
            _busy.value = false
        }
    }

    private fun writeText(context: Context, uri: Uri, text: String) {
        // "wt" truncates a file the picker let the user overwrite; not every provider supports it.
        val stream = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            context.contentResolver.openOutputStream(uri)
        } ?: throw IOException(uri.toString())
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun readText(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException(uri.toString())
        return stream.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytes.write(buffer, 0, n)
                if (bytes.size() > MAX_BYTES) throw IOException("file too large")
            }
            bytes.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        /** A settings file is a few KiB; anything larger is not one. */
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}

internal sealed class BackupOutcome {
    data class Saved(val withCredentials: Boolean) : BackupOutcome()
    data class Restored(val result: RestoreResult) : BackupOutcome()
    data class Failed(val error: Throwable) : BackupOutcome()
}

/** Keeps the runner alive across rotations of the settings tab. */
internal class BackupViewModel : ViewModel() {
    val runner = BackupRunner(viewModelScope)
}
