package com.jdandroid

import android.app.UiModeManager
import androidx.compose.ui.graphics.Color
import com.jdandroid.ui.GamerColorScheme
import com.jdandroid.ui.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fixed light/dark choice must reach the system night-mode override, or the splash follows the system. */
class ThemeModeTest {

    @Test
    fun festerModusErgibtNachtmodusDesSystems() {
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.DARK.systemNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeMode.LIGHT.systemNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeMode.SYSTEM.systemNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.GAMER.systemNightMode)
    }

    @Test
    fun gamerPaletteIstDunkelUndKontrastreich() {
        val s = GamerColorScheme
        assertTrue(luminance(s.background) < 0.05)
        listOf(
            s.onBackground to s.background, s.onSurface to s.surface,
            s.onSurfaceVariant to s.surfaceVariant, s.primary to s.surface,
            s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
            s.onSecondary to s.secondary, s.onSecondaryContainer to s.secondaryContainer,
            s.onTertiary to s.tertiary, s.onTertiaryContainer to s.tertiaryContainer,
            s.onError to s.error, s.onErrorContainer to s.errorContainer,
            s.onSurface to s.surfaceContainerHighest
        ).forEach { (fg, bg) ->
            assertTrue("$fg auf $bg", contrast(fg, bg) >= 4.5)
        }
    }

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    @Test
    fun schluesselOhneTrefferFolgtDemSystem() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
        assertEquals(ThemeMode.GAMER, ThemeMode.fromKey("gamer"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("unbekannt"))
    }
}
