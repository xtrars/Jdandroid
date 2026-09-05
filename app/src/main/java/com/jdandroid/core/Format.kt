package com.jdandroid.core

import java.util.Locale

/**
 * Binaere Einheiten mit korrekter Beschriftung (1 MiB = 1.048.576 Byte),
 * wie im JDownloader. Wird von Oberflaeche und Benachrichtigungen genutzt.
 * Das Dezimalzeichen folgt der Sprache des Geraets (Komma/Punkt), die
 * Einheiten bleiben unuebersetzt.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}
