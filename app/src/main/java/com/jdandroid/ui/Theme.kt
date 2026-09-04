package com.jdandroid.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Hell/Dunkel: dem System folgen oder fest waehlen (Einstellungen). */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Hell"),
    DARK("dark", "Dunkel");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/*
 * Eigene Palette statt der Material-Standardfarben: ein tiefes Petrol als
 * Primaerfarbe (Download-Manager, ruhig, technisch), ein warmes Bernstein
 * als Tertiaerfarbe fuer "fertig"/Erfolg. Neutrale Flaechen sind leicht
 * kuehl getoent, damit Karten und Hintergrund sich im Dunkelmodus noch
 * unterscheiden.
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1B6B86),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC3E8F7),
    onPrimaryContainer = Color(0xFF00202C),
    secondary = Color(0xFF4C6270),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE6F5),
    onSecondaryContainer = Color(0xFF081E2A),
    tertiary = Color(0xFF7A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9E),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F9FB),
    onBackground = Color(0xFF181C1F),
    surface = Color(0xFFF6F9FB),
    onSurface = Color(0xFF181C1F),
    surfaceVariant = Color(0xFFDCE4E9),
    onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787D),
    outlineVariant = Color(0xFFC0C8CD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFEAEFF2),
    surfaceContainerHigh = Color(0xFFE4E9EC),
    surfaceContainerHighest = Color(0xFFDEE3E6),
    inverseSurface = Color(0xFF2D3134),
    inverseOnSurface = Color(0xFFEEF1F4),
    inversePrimary = Color(0xFF8ED2EC)
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8ED2EC),
    onPrimary = Color(0xFF003847),
    primaryContainer = Color(0xFF004E63),
    onPrimaryContainer = Color(0xFFC3E8F7),
    secondary = Color(0xFFB3CAD9),
    onSecondary = Color(0xFF1E333F),
    secondaryContainer = Color(0xFF344A57),
    onSecondaryContainer = Color(0xFFCFE6F5),
    tertiary = Color(0xFFF2C14E),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF9E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDFE3E6),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDFE3E6),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC0C8CD),
    outline = Color(0xFF8A9297),
    outlineVariant = Color(0xFF41484D),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF252A2E),
    surfaceContainerHighest = Color(0xFF303539),
    inverseSurface = Color(0xFFDFE3E6),
    inverseOnSurface = Color(0xFF2D3134),
    inversePrimary = Color(0xFF1B6B86)
)

private val JdTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp)
    )
}

/** Ist der Dunkelmodus fuer diese Einstellung aktiv? */
@Composable
fun isDarkFor(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun JdTheme(
    mode: ThemeMode,
    dynamicColors: Boolean,
    content: @Composable () -> Unit
) {
    val dark = isDarkFor(mode)
    val context = LocalContext.current
    val scheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = JdTypography, content = content)
}
