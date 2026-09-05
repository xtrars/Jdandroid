package com.jdandroid.core

import java.util.Locale

/**
 * Binary units (1 MiB = 1,048,576 bytes). The decimal separator follows the
 * device locale; the unit labels are not translated.
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

/**
 * "1.5" + "GB", "1,5" + "Go", "700" + "MB" → bytes (1024-based); -1 for an
 * unreadable number. Only the unit's first letter counts, so the French
 * octet forms ("Go", "o") work like "GB" and "B".
 */
fun parseSize(value: String, unit: String): Long {
    val number = value.replace(',', '.').toDoubleOrNull() ?: return -1
    val factor = when (unit.uppercase().firstOrNull()) {
        'K' -> 1L shl 10
        'M' -> 1L shl 20
        'G' -> 1L shl 30
        'T' -> 1L shl 40
        else -> 1L
    }
    return (number * factor).toLong()
}
