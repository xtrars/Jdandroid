package com.jdandroid.ui

import com.jdandroid.engine.nfs.NfsDiscovery
import com.jdandroid.engine.nfs.NfsExports
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the server search dialog shows. [selected] switches to the second
 * level, the export list of that server; [error] belongs to that level.
 */
internal data class NfsDiscoveryState(
    val searching: Boolean = false,
    val servers: List<NfsServer> = emptyList(),
    val selected: NfsServer? = null,
    val loadingExports: Boolean = false,
    val exports: List<String> = emptyList(),
    val error: NfsBrowserError? = null
)

/**
 * Holds the server search outside the composition. Null state means the
 * dialog is closed; a running search or export listing is cancelled on close.
 */
internal class NfsDiscoveryRunner(
    private val scope: CoroutineScope,
    private val discover: suspend ((NfsServer) -> Unit) -> Unit,
    private val exports: suspend (String) -> List<String> = NfsExports::exports
) {
    private val _state = MutableStateFlow<NfsDiscoveryState?>(null)
    val state: StateFlow<NfsDiscoveryState?> = _state

    private var searchJob: Job? = null
    private var exportsJob: Job? = null

    /** Opens the dialog at the server list and starts (or restarts) the search. */
    fun search() {
        if (_state.value?.searching == true) return
        exportsJob?.cancel()
        _state.value = NfsDiscoveryState(searching = true)
        searchJob = scope.launch {
            runCatching {
                discover { server -> _state.update { it?.copy(servers = NfsDiscovery.merge(it.servers, server)) } }
            }
            ensureActive()
            _state.update { it?.copy(searching = false) }
        }
    }

    /** Second level: lists the exports of [server]; also used for a typed-in host. */
    fun select(server: NfsServer) {
        exportsJob?.cancel()
        _state.update { (it ?: NfsDiscoveryState()).copy(selected = server, loadingExports = true, exports = emptyList(), error = null) }
        exportsJob = scope.launch {
            val result = runCatching { exports(server.host) }
            ensureActive()
            _state.update { current ->
                if (current?.selected != server) return@update current
                result.fold(
                    onSuccess = { current.copy(loadingExports = false, exports = it) },
                    onFailure = { e -> current.copy(loadingExports = false, error = errorOf(e)) }
                )
            }
        }
    }

    /** Back to the server list; starts a search when there is none yet (dialog opened at a typed host). */
    fun back() {
        exportsJob?.cancel()
        val current = _state.value ?: return
        if (current.servers.isEmpty() && !current.searching) {
            search()
        } else {
            _state.value = current.copy(selected = null, loadingExports = false, exports = emptyList(), error = null)
        }
    }

    fun close() {
        searchJob?.cancel()
        exportsJob?.cancel()
        searchJob = null
        exportsJob = null
        _state.value = null
    }

    private companion object {
        fun errorOf(e: Throwable): NfsBrowserError {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return NfsBrowserError(message, e is NfsFailure.Transient)
        }
    }
}
