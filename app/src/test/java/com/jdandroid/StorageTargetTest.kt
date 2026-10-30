package com.jdandroid

import com.jdandroid.engine.StorageTarget
import com.jdandroid.engine.StorageTarget.ExportedAt
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

    @Test
    fun ablageWirdAusDemAnzeigepfadErkannt() {
        assertEquals(ExportedAt.APP_FOLDER, StorageTarget.exportedAt("/data/user/0/app/files/downloads/x.rar", "Filme"))
        assertEquals(ExportedAt.NFS, StorageTarget.exportedAt("nfs://host/share/x.rar", "Filme"))
        assertEquals(ExportedAt.MEDIA_STORE, StorageTarget.exportedAt("Downloads/JDAndroid/x.rar", null))
        assertEquals(ExportedAt.SAF, StorageTarget.exportedAt("Filme/x.rar", "Filme"))
        assertEquals(ExportedAt.UNKNOWN, StorageTarget.exportedAt("Filme/x.rar", null))
    }

    @Test
    fun spaeterGewaehlterZielordnerLoeschtNichtDieDownloadsKopie() {
        // Exported to Downloads before a SAF folder was chosen: only the MediaStore copy may go
        assertEquals(ExportedAt.MEDIA_STORE, StorageTarget.exportedAt("Downloads/JDAndroid/x.rar", "Filme"))
        // Exported to a former SAF folder: never a same-named file in the current one
        assertEquals(ExportedAt.UNKNOWN, StorageTarget.exportedAt("Alt/x.rar", "Filme"))
    }
}
