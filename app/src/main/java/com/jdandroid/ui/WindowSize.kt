package com.jdandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/**
 * Hoehe des Fensters in dp aus [LocalWindowInfo] (statt der veralteten
 * Konfigurationsbreite/-hoehe): gilt auch im Mehrfenster-Modus und bei
 * Groessenaenderungen, weil sie sich auf das Fenster bezieht, nicht auf
 * den Bildschirm.
 */
@Composable
fun windowHeightDp(): Dp {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { size.height.toDp() }
}
