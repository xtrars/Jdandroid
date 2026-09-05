package com.jdandroid.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live-Werte eines Eintrags, die nicht in die Datenbank gehoeren: Bytestand
 * und Geschwindigkeit waehrend des Ladens, Prozent waehrend des Entpackens.
 * -1 bedeutet "kein Wert" - das Feld wird dann nicht ueber den Datenbankwert gelegt.
 */
data class LiveProgress(
    val downloadedBytes: Long = -1,
    val speedBps: Long = 0,
    val extractPercent: Int = -1
)

/**
 * Prozessweiter Speicher-Bus fuer Fortschrittswerte. Frueher schrieb die
 * Engine alle 2 s Bytes/Geschwindigkeit und sekuendlich den Entpack-Stand in
 * Room; jede Schreibung invalidierte die ganze Tabelle, und die Oberflaeche
 * gruppierte die Liste neu. Jetzt liegen Live-Werte nur hier; die Datenbank
 * sieht nur echte Zustandswechsel und eine seltene Sicherung des Bytestands.
 *
 * Je Eintrag wird hoechstens alle [MIN_INTERVAL_MS] veroeffentlicht; ein
 * entfernter Eintrag (Pause, Abschluss, Fehler) verschwindet sofort.
 */
object ProgressBus {
    /** Mindestabstand zweier Veroeffentlichungen je Eintrag. */
    const val MIN_INTERVAL_MS = 500L

    private val _state = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val state: StateFlow<Map<Long, LiveProgress>> = _state

    /** Zeitpunkt der letzten Veroeffentlichung je Eintrag (Drosselung). */
    private val lastPublished = HashMap<Long, Long>()
    private val lock = Any()

    /**
     * Wert veroeffentlichen, sofern seit der letzten Veroeffentlichung dieses
     * Eintrags mindestens [MIN_INTERVAL_MS] vergangen sind. Liefert true, wenn
     * der Wert uebernommen wurde. [now] ist monotone Zeit in Millisekunden
     * ([Clock]) und fuer Tests ueberschreibbar.
     */
    fun update(id: Long, progress: LiveProgress, now: Long = Clock.SYSTEM.nowMillis()): Boolean {
        synchronized(lock) {
            val last = lastPublished[id]
            if (last != null && now - last < MIN_INTERVAL_MS) return false
            lastPublished[id] = now
            if (_state.value[id] == progress) return true
            _state.value = _state.value + (id to progress)
            return true
        }
    }

    /** Eintrag entfernen (Pause, Abschluss, Fehler): die Datenbank ist wieder massgeblich. */
    fun remove(id: Long) = removeAll(listOf(id))

    fun removeAll(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            ids.forEach { lastPublished.remove(it) }
            if (ids.none { it in _state.value }) return
            _state.value = _state.value - ids.toSet()
        }
    }

    /** Summe der aktuellen Geschwindigkeiten (fuer die Benachrichtigung). */
    fun totalSpeedBps(): Long = _state.value.values.sumOf { it.speedBps }

    /** Nur fuer Tests: alles zuruecksetzen. */
    internal fun clear() {
        synchronized(lock) {
            lastPublished.clear()
            _state.value = emptyMap()
        }
    }
}
