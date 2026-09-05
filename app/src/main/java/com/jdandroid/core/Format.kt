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
