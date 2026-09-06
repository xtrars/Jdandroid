package com.jdandroid

import android.app.UiModeManager
import com.jdandroid.ui.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** A fixed light/dark choice must reach the system night-mode override, or the splash follows the system. */
class ThemeModeTest {

    @Test
    fun festerModusErgibtNachtmodusDesSystems() {
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeMode.DARK.systemNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeMode.LIGHT.systemNightMode)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeMode.SYSTEM.systemNightMode)
    }

    @Test
    fun schluesselOhneTrefferFolgtDemSystem() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("unbekannt"))
    }
}
