package com.jdandroid.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide registry of running extractions. Extraction continues
 * NonCancellable across a service restart, so a new service instance must
 * neither requeue nor extract the same archive a second time.
 */
internal object ExtractionRegistry {
    /** Running extractions across all service instances. */
    val count = AtomicInteger()

    private val bases = HashSet<String>()
    private val ids = HashSet<Long>()

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

    /** Entries currently being extracted; a starting service must not requeue them. */
    @Synchronized
    fun activeIds(): List<Long> = ids.toList()
}
