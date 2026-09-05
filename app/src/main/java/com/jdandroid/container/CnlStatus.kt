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

    /** Letzte eingegangene Anfrage (Uhrzeit, Methode, Pfad, Ergebnis) - eine Zeile. */
    private val _lastRequest = MutableStateFlow<String?>(null)
    val lastRequest: StateFlow<String?> = _lastRequest

    fun record(method: String, uri: String, result: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _lastRequest.value = ContainerTexts.t("service_cnl_last_request_line", time, method, uri, result)
    }

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
