package com.jdandroid.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Prozessweiter Stand laufender Entpackvorgaenge. Das Entpacken laeuft
 * NonCancellable weiter, auch wenn der Dienst sich beendet und neu startet;
 * eine neue Dienst-Instanz darf dasselbe Archiv dann weder erneut einreihen
 * noch ein zweites Mal entpacken (das passierte: Archiv doppelt entpackt).
 */
internal object ExtractionRegistry {
    /** Anzahl laufender Vorgaenge (fuer activeCount/isIdle aller Instanzen). */
    val count = AtomicInteger()

    private val bases = HashSet<String>()
    private val ids = HashSet<Long>()

    /** Vorgang fuer [base] anmelden; false, wenn er bereits laeuft. */
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

    /** Eintraege, die gerade entpackt werden - beim Dienststart nicht neu einreihen. */
    @Synchronized
    fun activeIds(): List<Long> = ids.toList()
}
