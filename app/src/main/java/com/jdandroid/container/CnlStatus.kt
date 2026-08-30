package com.jdandroid.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sichtbarer Laufzeitzustand des Click'n'Load-Servers, damit in den
 * Einstellungen steht, ob wirklich jemand auf dem Port lauscht - und nicht
 * nur, ob der Schalter an ist.
 */
object CnlStatus {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun set(running: Boolean) { _running.value = running }
}
