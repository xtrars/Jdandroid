package com.jdandroid.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Meldungen an den Nutzer ("DLC wird importiert", "3 Links übernommen",
 * Fehler beim Speichern eines Kontos) laufen ueber einen zentralen Kanal.
 * Er liegt bewusst unterhalb der Oberflaeche: Engine und Hintergrunddienste
 * melden hierhin, ohne die UI-Schicht zu kennen; die Darstellung uebernimmt
 * die MainActivity.
 */
enum class MessageKind { INFO, PROGRESS, SUCCESS, ERROR }

data class AppMessage(val text: String, val kind: MessageKind = MessageKind.INFO)

object AppMessages {
    // replay = 1: eine Meldung, die vor dem Aufbau der Oberflaeche entsteht
    // (z.B. "keine DLC-Datei" beim Kaltstart per Intent), geht nicht verloren.
    private val _events = MutableSharedFlow<AppMessage>(replay = 1, extraBufferCapacity = 16)
    val events: SharedFlow<AppMessage> = _events

    /** Nach der Anzeige aufrufen, damit die Meldung nicht erneut erscheint. */
    fun markShown() = _events.resetReplayCache()

    fun post(text: String, kind: MessageKind = MessageKind.INFO) {
        _events.tryEmit(AppMessage(text, kind))
    }

    fun info(text: String) = post(text, MessageKind.INFO)
    fun progress(text: String) = post(text, MessageKind.PROGRESS)
    fun success(text: String) = post(text, MessageKind.SUCCESS)
    fun error(text: String) = post(text, MessageKind.ERROR)
}
