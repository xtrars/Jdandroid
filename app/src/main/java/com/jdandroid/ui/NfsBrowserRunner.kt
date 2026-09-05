package com.jdandroid.ui

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsEntry
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsShares
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the folder browser dialog shows; [path] is relative to the export root ("" = root). */
internal data class NfsBrowserState(
    val path: String = "",
    val entries: List<NfsEntry> = emptyList(),
    val loading: Boolean = false,
    val error: NfsBrowserError? = null
)

/** One error line; [unreachable] marks a transient failure (NAS off, network). */
internal data class NfsBrowserError(val message: String, val unreachable: Boolean)

/**
 * Holds the folder browser outside the composition so a rotation keeps the
 * current folder and a running listing. Null state means the dialog is closed.
 */
internal class NfsBrowserRunner(
    private val scope: CoroutineScope,
    private val browse: suspend (NfsSettings, String) -> List<NfsEntry> = NfsShares::browse,
    private val mkdir: suspend (NfsSettings, String) -> Unit = NfsShares::mkdir
) {
    private val _state = MutableStateFlow<NfsBrowserState?>(null)
    val state: StateFlow<NfsBrowserState?> = _state

    private var settings = NfsSettings()
    private var job: Job? = null

    /** Opens the dialog at the export root of [target]. */
    fun open(target: NfsSettings) {
        job?.cancel()
        settings = target
        _state.value = NfsBrowserState()
        load("")
    }

    fun close() {
        job?.cancel()
        job = null
        _state.value = null
    }

    fun enter(name: String) {
        val current = _state.value ?: return
        if (current.loading || !NfsSettings.isValidName(name)) return
        load(NfsSettings.joinPath(current.path, name))
    }

    fun up() {
        val current = _state.value ?: return
        if (current.loading || current.path.isEmpty()) return
        load(NfsSettings.parentPath(current.path))
    }

    /** Creates [name] below the current folder and lists it again. */
    fun createFolder(name: String) {
        val current = _state.value ?: return
        val trimmed = name.trim()
        if (current.loading || !NfsSettings.isValidName(trimmed)) return
        val target = NfsSettings.joinPath(current.path, trimmed)
        run(current.path) { mkdir(settings, target) }
    }

    private fun load(path: String) = run(path) {}

    private fun run(path: String, before: suspend () -> Unit) {
        _state.value = NfsBrowserState(path = path, loading = true)
        job = scope.launch {
            val result = runCatching { before(); browse(settings, path) }
            // A closed dialog must not reappear with the error of its cancelled listing.
            ensureActive()
            _state.value = result.fold(
                onSuccess = { NfsBrowserState(path = path, entries = sorted(it)) },
                onFailure = { e -> NfsBrowserState(path = path, error = errorOf(e)) }
            )
        }
    }

    private companion object {
        /** Directories first, then names case-insensitively. */
        fun sorted(entries: List<NfsEntry>): List<NfsEntry> =
            entries.sortedWith(compareBy<NfsEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })

        fun errorOf(e: Throwable): NfsBrowserError {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return NfsBrowserError(message, e is NfsFailure.Transient)
        }
    }
}
