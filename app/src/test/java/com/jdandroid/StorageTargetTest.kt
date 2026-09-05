package com.jdandroid

import com.jdandroid.engine.StorageTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exported files are fetched back by the name they were stored under, taken from the display path. */
class StorageTargetTest {

    @Test
    fun gespeicherterNameKommtAusDemAnzeigepfadMitKollisionssuffix() {
        assertEquals("Setup.part1 (1).rar", StorageTarget.storedName("Downloads/JDAndroid/Setup.part1 (1).rar"))
        assertEquals("Setup.part1 (1).rar", StorageTarget.storedName("Zielordner/Setup.part1 (1).rar"))
        assertEquals("Setup.part1 (2).rar", StorageTarget.storedName("nfs://host/share/dl/Setup.part1 (2).rar"))
        assertEquals("x.rar", StorageTarget.storedName("/data/user/0/app/files/downloads/x.rar"))
    }

    @Test
    fun nameOhneOrdnerBleibtUnveraendert() {
        assertEquals("x.rar", StorageTarget.storedName("x.rar"))
    }
}
