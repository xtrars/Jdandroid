package com.jdandroid.nfs

import com.jdandroid.engine.nfs.NfsModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directories get 0755 and files 0644; only a directory without any permission bit counts as sealed. */
class NfsModesTest {

    @Test
    fun modiEntsprechenUmask022() {
        assertEquals(493L, NfsModes.DIR)
        assertEquals(420L, NfsModes.FILE)
        assertEquals("0755", "0" + java.lang.Long.toOctalString(NfsModes.DIR))
        assertEquals("0644", "0" + java.lang.Long.toOctalString(NfsModes.FILE))
    }

    @Test
    fun nurOhneJedesRechtebitGiltAlsVersiegelt() {
        assertTrue(NfsModes.sealed(0L))
        assertTrue(NfsModes.sealed(0b100_000_000_000L))
        assertFalse(NfsModes.sealed(0b100_000_000L))
        assertFalse(NfsModes.sealed(NfsModes.DIR))
        assertFalse(NfsModes.sealed(0b101_101_101L))
    }

    @Test
    fun attributeTragenNurDenModus() {
        val text = NfsModes.attributes(NfsModes.DIR).toString()
        assertTrue(text, text.contains("493") || text.contains("755"))
    }
}
