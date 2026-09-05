package com.jdandroid.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Central channel for user-facing messages. It sits below the UI layer so
 * the engine and background services can post without knowing it; the
 * MainActivity renders them.
 */
enum class MessageKind { INFO, PROGRESS, SUCCESS, ERROR }

data class AppMessage(val text: String, val kind: MessageKind = MessageKind.INFO)

object AppMessages {
    // replay = 1: a message posted before the UI exists (e.g. a cold start
    // via intent) is not lost.
    private val _events = MutableSharedFlow<AppMessage>(replay = 1, extraBufferCapacity = 16)
    val events: SharedFlow<AppMessage> = _events

    /** Call after display so the message is not replayed. */
    fun markShown() = _events.resetReplayCache()

    fun post(text: String, kind: MessageKind = MessageKind.INFO) {
        _events.tryEmit(AppMessage(text, kind))
    }

    fun info(text: String) = post(text, MessageKind.INFO)
    fun progress(text: String) = post(text, MessageKind.PROGRESS)
    fun success(text: String) = post(text, MessageKind.SUCCESS)
    fun error(text: String) = post(text, MessageKind.ERROR)
}
