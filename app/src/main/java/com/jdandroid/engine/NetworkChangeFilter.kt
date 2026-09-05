package com.jdandroid.engine

/**
 * Reduces the stream of default-network callbacks to real changes: a new
 * network, or a change of the metered/validated state. Bandwidth and signal
 * updates arrive every few seconds on Wi-Fi and must not trigger anything.
 */
internal class NetworkChangeFilter {
    private data class Key(val network: Any, val notMetered: Boolean, val validated: Boolean)

    private var last: Key? = null

    /** A network became the default; the next capabilities report always counts as a change. */
    fun onAvailable() {
        last = null
    }

    /** True when [network], [notMetered] or [validated] differ from the last report. */
    fun onCapabilities(network: Any, notMetered: Boolean, validated: Boolean): Boolean {
        val key = Key(network, notMetered, validated)
        if (key == last) return false
        last = key
        return true
    }
}
