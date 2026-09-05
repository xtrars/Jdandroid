package com.jdandroid.core

/**
 * Monotone Uhr fuer Zeitdifferenzen (Geschwindigkeitsmessung, Drosselung,
 * Wartefenster). Die Wanduhr (System.currentTimeMillis) springt bei
 * Zeitkorrekturen des Systems und bleibt deshalb den persistierten
 * Zeitstempeln (retryAt, Anzeige) vorbehalten. Injizierbar, damit Tests die
 * Zeit selbst vorstellen koennen statt zu schlafen.
 */
fun interface Clock {
    /** Monotone Zeit in Nanosekunden; nur Differenzen sind aussagekraeftig. */
    fun nowNanos(): Long

    /** Monotone Zeit in Millisekunden, fuer Vergleiche mit Millisekunden-Konstanten. */
    fun nowMillis(): Long = nowNanos() / 1_000_000L

    companion object {
        /** Standard: System.nanoTime, unabhaengig von der Wanduhr. */
        val SYSTEM: Clock = Clock(System::nanoTime)
    }
}
