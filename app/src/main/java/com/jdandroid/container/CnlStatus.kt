package com.jdandroid.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sichtbarer Laufzeitzustand des Click'n'Load-Servers: ob wirklich jemand auf
 * dem Port lauscht - und, falls nicht, warum. Ohne das sieht der Nutzer bei
 * einem fehlgeschlagenen Start nur "läuft nicht" ohne Grund.
 */
object CnlStatus {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _boundTo = MutableStateFlow<String?>(null)
    val boundTo: StateFlow<String?> = _boundTo

    fun started(address: String) {
        _running.value = true
        _error.value = null
        _boundTo.value = address
    }

    fun stopped() {
        _running.value = false
        _error.value = null
        _boundTo.value = null
    }

    fun failed(reason: String) {
        _running.value = false
        _error.value = reason
        _boundTo.value = null
    }
}
