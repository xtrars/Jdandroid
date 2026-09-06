package com.jdandroid.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Light/dark preference: follow the system or a fixed choice. */
enum class ThemeMode(val key: String, val systemNightMode: Int) {
    SYSTEM("system", UiModeManager.MODE_NIGHT_AUTO),
    LIGHT("light", UiModeManager.MODE_NIGHT_NO),
    DARK("dark", UiModeManager.MODE_NIGHT_YES);

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Mirrors the chosen mode into the app's night-mode override so the splash
 * screen and window background (drawn from the XML theme before Compose
 * runs) match it. Below API 31 they keep following the system.
 */
fun applySystemNightMode(context: Context, mode: ThemeMode) {
    if (Build.VERSION.SDK_INT < 31) return
    runCatching {
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode.systemNightMode)
    }
}

private val JdTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp)
    )
}

@Composable
fun isDarkFor(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Material You only: dynamic colors from Android 12 on, the Material
 * baseline scheme below. No custom palette.
 */
@Composable
fun JdTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit
) {
    val dark = isDarkFor(mode)
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, typography = JdTypography, content = content)
}
