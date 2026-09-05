package com.jdandroid.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live values of an entry that stay out of the database: bytes and speed
 * while downloading, percent while extracting. -1 means "no value" and does
 * not override the database value.
 */
data class LiveProgress(
    val downloadedBytes: Long = -1,
    val speedBps: Long = 0,
    val extractPercent: Int = -1
)

/**
 * In-memory bus for progress values. Writing them to Room would invalidate
 * the whole table on every update and make the UI regroup the list, so the
 * database only sees real state changes and an occasional byte snapshot.
 * Each entry publishes at most every [MIN_INTERVAL_MS]; removal is immediate.
 */
object ProgressBus {
    const val MIN_INTERVAL_MS = 500L

    private val _state = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val state: StateFlow<Map<Long, LiveProgress>> = _state

    private val lastPublished = HashMap<Long, Long>()
    private val lock = Any()

    /**
     * Publishes unless the entry was published less than [MIN_INTERVAL_MS]
     * ago; returns true when accepted. [now] is monotonic milliseconds ([Clock]).
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

    /** Removes the entry; the database value applies again. */
    fun remove(id: Long) = removeAll(listOf(id))

    fun removeAll(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            ids.forEach { lastPublished.remove(it) }
            if (ids.none { it in _state.value }) return
            _state.value = _state.value - ids.toSet()
        }
    }

    fun totalSpeedBps(): Long = _state.value.values.sumOf { it.speedBps }

    /** Test use only. */
    internal fun clear() {
        synchronized(lock) {
            lastPublished.clear()
            _state.value = emptyMap()
        }
    }
}
