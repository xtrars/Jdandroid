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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colour scheme: follow the system, a fixed light or dark Material You scheme, or the neon gamer palette. */
enum class ThemeMode(val key: String, val systemNightMode: Int) {
    SYSTEM("system", UiModeManager.MODE_NIGHT_AUTO),
    LIGHT("light", UiModeManager.MODE_NIGHT_NO),
    DARK("dark", UiModeManager.MODE_NIGHT_YES),
    GAMER("gamer", UiModeManager.MODE_NIGHT_YES);

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
    ThemeMode.DARK, ThemeMode.GAMER -> true
}

/**
 * Deep-space gamer palette: near-black blue surfaces with neon cyan, magenta
 * and lime accents. The only scheme that is not Material You; all tones keep
 * at least 4.5:1 against their container.
 */
val GamerColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00232B),
    primaryContainer = Color(0xFF003F4C),
    onPrimaryContainer = Color(0xFFA6F4FF),
    inversePrimary = Color(0xFF006676),
    secondary = Color(0xFFFF4FD8),
    onSecondary = Color(0xFF3B0030),
    secondaryContainer = Color(0xFF5A0F4C),
    onSecondaryContainer = Color(0xFFFFC7F0),
    tertiary = Color(0xFF8CFF5A),
    onTertiary = Color(0xFF0B3300),
    tertiaryContainer = Color(0xFF1E4D0E),
    onTertiaryContainer = Color(0xFFCDFFB6),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF3F0012),
    errorContainer = Color(0xFF6E1027),
    onErrorContainer = Color(0xFFFFD1D9),
    background = Color(0xFF070B1A),
    onBackground = Color(0xFFE4EDFF),
    surface = Color(0xFF070B1A),
    onSurface = Color(0xFFE4EDFF),
    surfaceVariant = Color(0xFF1A2342),
    onSurfaceVariant = Color(0xFFB6C3E6),
    surfaceTint = Color(0xFF00E5FF),
    inverseSurface = Color(0xFFE4EDFF),
    inverseOnSurface = Color(0xFF0E1530),
    outline = Color(0xFF6B7BB0),
    outlineVariant = Color(0xFF2A3560),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1C2546),
    surfaceDim = Color(0xFF050812),
    surfaceContainerLowest = Color(0xFF03050D),
    surfaceContainerLow = Color(0xFF0B1124),
    surfaceContainer = Color(0xFF0F1730),
    surfaceContainerHigh = Color(0xFF151F3C),
    surfaceContainerHighest = Color(0xFF1B2648)
)

/**
 * Material You for system, light and dark: dynamic colors from Android 12
 * on, the Material baseline scheme below. Gamer uses [GamerColorScheme].
 */
@Composable
fun JdTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit
) {
    val dark = isDarkFor(mode)
    val context = LocalContext.current
    val scheme = when {
        mode == ThemeMode.GAMER -> GamerColorScheme
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val gamer = mode == ThemeMode.GAMER
    CompositionLocalProvider(LocalGamer provides gamer) {
        MaterialTheme(
            colorScheme = scheme,
            typography = if (gamer) gamerTypography(JdTypography) else JdTypography,
            content = content
        )
    }
}
