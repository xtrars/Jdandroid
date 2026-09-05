package com.jdandroid.ui

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsProbe
import com.jdandroid.engine.nfs.NfsShares
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs the NFS connection check outside the composition, so a rotation
 * neither cancels a running check nor drops its result.
 */
internal class NfsProbeRunner(
    private val scope: CoroutineScope,
    private val probe: suspend (NfsSettings) -> NfsProbe = NfsShares::probe
) {
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing

    /** Result of the last finished check; null while none finished or a new one runs. */
    private val _outcome = MutableStateFlow<NfsProbeOutcome?>(null)
    val outcome: StateFlow<NfsProbeOutcome?> = _outcome

    /** Starts a check for [target]; ignored while one is still running. */
    fun start(target: NfsSettings) {
        if (!_probing.compareAndSet(expect = false, update = true)) return
        _outcome.value = null
        scope.launch {
            _outcome.value = NfsProbeOutcome.of(runCatching { probe(target) })
            _probing.value = false
        }
    }
}

/** Outcome of a connection check, reduced to what the single result line shows. */
internal sealed class NfsProbeOutcome {
    data class Ok(val entries: Int, val freeBytes: Long, val totalBytes: Long) : NfsProbeOutcome()
    /** NAS off or unreachable; shown with a "not reachable" prefix. */
    data class Unreachable(val message: String) : NfsProbeOutcome()
    data class Failed(val message: String) : NfsProbeOutcome()

    companion object {
        fun of(result: Result<NfsProbe>): NfsProbeOutcome = result.fold(
            onSuccess = { Ok(it.entries.size, it.freeBytes, it.totalBytes) },
            onFailure = { e ->
                val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                if (e is NfsFailure.Transient) Unreachable(message) else Failed(message)
            }
        )
    }
}
