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
 * Each entry publishes at most every [MIN_INTERVAL_MS], and the map itself
 * is emitted at most that often as well: with many entries transferring at
 * once, their updates are coalesced into one emission instead of one per
 * entry. Removal is immediate.
 */
object ProgressBus {
    const val MIN_INTERVAL_MS = 500L

    private val _state = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val state: StateFlow<Map<Long, LiveProgress>> = _state

    private val lastPublished = HashMap<Long, Long>()
    private val pending = HashMap<Long, LiveProgress>()
    private var lastEmit = Long.MIN_VALUE / 2
    private val lock = Any()

    /**
     * Publishes unless the entry was published less than [MIN_INTERVAL_MS]
     * ago; returns true when accepted. An accepted value becomes visible with
     * the next emission, at the latest [MIN_INTERVAL_MS] after the previous
     * one. [now] is monotonic milliseconds ([Clock]).
     */
    fun update(id: Long, progress: LiveProgress, now: Long = Clock.SYSTEM.nowMillis()): Boolean {
        synchronized(lock) {
            val last = lastPublished[id]
            if (last != null && now - last < MIN_INTERVAL_MS) return false
            lastPublished[id] = now
            pending[id] = progress
            if (now - lastEmit >= MIN_INTERVAL_MS) flush(now)
            return true
        }
    }

    /** Removes the entry; the database value applies again. */
    fun remove(id: Long) = removeAll(listOf(id))

    fun removeAll(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            ids.forEach { lastPublished.remove(it); pending.remove(it) }
            val next = _state.value - ids.toSet() + pending
            pending.clear()
            if (next != _state.value) _state.value = next
        }
    }

    /** Sum over emitted and still pending entries, so the notification never lags the coalescing. */
    fun totalSpeedBps(): Long = synchronized(lock) { (_state.value + pending).values.sumOf { it.speedBps } }

    private fun flush(now: Long) {
        lastEmit = now
        if (pending.isEmpty()) return
        val next = _state.value + pending
        pending.clear()
        if (next != _state.value) _state.value = next
    }

    /** Test use only. */
    internal fun clear() {
        synchronized(lock) {
            lastPublished.clear()
            pending.clear()
            lastEmit = Long.MIN_VALUE / 2
            _state.value = emptyMap()
        }
    }
}
