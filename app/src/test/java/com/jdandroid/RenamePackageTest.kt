package com.jdandroid

import com.jdandroid.ui.cleanPackageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Input handling of the shared package rename dialog. */
class RenamePackageTest {

    @Test
    fun nameWirdGetrimmt() {
        assertEquals("Serie S01", cleanPackageName("  Serie S01 \n"))
        assertEquals("Serie S01", cleanPackageName("Serie S01"))
    }

    @Test
    fun leererNameIstUngueltig() {
        assertNull(cleanPackageName(""))
        assertNull(cleanPackageName("   "))
        assertNull(cleanPackageName("\t\n"))
    }
}
