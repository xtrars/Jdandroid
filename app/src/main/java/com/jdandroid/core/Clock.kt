package com.jdandroid.core

/**
 * Monotonic clock for time differences (speed, throttling, wait windows).
 * The wall clock jumps on system time corrections and is reserved for
 * persisted timestamps. Injectable so tests advance time instead of sleeping.
 */
fun interface Clock {
    /** Monotonic nanoseconds; only differences are meaningful. */
    fun nowNanos(): Long

    fun nowMillis(): Long = nowNanos() / 1_000_000L

    companion object {
        val SYSTEM: Clock = Clock(System::nanoTime)
    }
}
