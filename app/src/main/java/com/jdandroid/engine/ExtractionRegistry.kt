package com.jdandroid.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide registry of running extractions and exports of finished
 * files. Both continue NonCancellable across a service restart, so a new
 * service instance must neither requeue nor extract the same archive a second
 * time, and no pump may restart an entry whose file is still being exported.
 */
internal object ExtractionRegistry {
    /** Running extractions across all service instances. */
    val count = AtomicInteger()

    private val bases = HashSet<String>()
    private val ids = HashSet<Long>()
    private val exporting = HashSet<Long>()

    /** Registers an extraction for [base]; false if one is already running. */
    @Synchronized
    fun start(base: String, setIds: Collection<Long>): Boolean {
        if (!bases.add(base)) return false
        ids.addAll(setIds)
        return true
    }

    @Synchronized
    fun finish(base: String, setIds: Collection<Long>) {
        bases.remove(base)
        ids.removeAll(setIds.toSet())
    }

    @Synchronized
    fun isActive(base: String): Boolean = base in bases

    @Synchronized
    fun startExport(id: Long) { exporting.add(id) }

    @Synchronized
    fun finishExport(id: Long) { exporting.remove(id) }

    /** Entries whose finished file is being copied or uploaded outside the completion lock. */
    @Synchronized
    fun exportingIds(): List<Long> = exporting.toList()

    /** Entries currently being extracted or exported; a starting service must not requeue them. */
    @Synchronized
    fun activeIds(): List<Long> = (ids + exporting).toList()
}
