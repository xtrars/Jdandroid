package com.jdandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/**
 * Window height in dp from [LocalWindowInfo]; unlike the configuration
 * values it refers to the window, so multi-window mode is handled.
 */
@Composable
fun windowHeightDp(): Dp {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { size.height.toDp() }
}
