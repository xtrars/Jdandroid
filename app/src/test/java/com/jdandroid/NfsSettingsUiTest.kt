package com.jdandroid

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsProbe
import com.jdandroid.ui.NfsProbeOutcome
import com.jdandroid.ui.NfsSettingsUi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Pure parts of the NFS settings section: id parsing and the connection-check result line. */
class NfsSettingsUiTest {

    @Test
    fun idFeldNurZiffern() {
        assertEquals("1000", NfsSettingsUi.cleanId("1000"))
        assertEquals("1026", NfsSettingsUi.cleanId("10 26abc"))
        assertEquals("", NfsSettingsUi.cleanId("-"))
        assertEquals("1234567890", NfsSettingsUi.cleanId("12345678901234"))
    }

    @Test
    fun idMitRueckfallAuf1000() {
        assertEquals(1026, NfsSettingsUi.parseId("1026", NfsSettings.DEFAULT_UID))
        assertEquals(0, NfsSettingsUi.parseId("0", NfsSettings.DEFAULT_UID))
        assertEquals(1000, NfsSettingsUi.parseId("", NfsSettings.DEFAULT_UID))
        assertEquals(1000, NfsSettingsUi.parseId(" ", NfsSettings.DEFAULT_GID))
        assertEquals(1000, NfsSettingsUi.parseId("99999999999", NfsSettings.DEFAULT_UID))
    }

    @Test
    fun erfolgZaehltEintraegeUndPlatz() {
        val probe = NfsProbe(listOf("a", "b", "c"), freeBytes = 1024, totalBytes = 4096)
        assertEquals(NfsProbeOutcome.Ok(3, 1024, 4096), NfsProbeOutcome.of(Result.success(probe)))
    }

    @Test
    fun voruebergehendWirdAlsNichtErreichbarGemeldet() {
        val outcome = NfsProbeOutcome.of(Result.failure(NfsFailure.Transient("timeout")))
        assertEquals(NfsProbeOutcome.Unreachable("timeout"), outcome)
    }

    @Test
    fun dauerhaftUndIoZeigenDieMeldung() {
        assertEquals(
            NfsProbeOutcome.Failed("export missing"),
            NfsProbeOutcome.of(Result.failure(NfsFailure.Permanent("export missing")))
        )
        assertEquals(NfsProbeOutcome.Failed("refused"), NfsProbeOutcome.of(Result.failure(IOException("refused"))))
    }

    @Test
    fun leereMeldungFaelltAufKlassennamen() {
        assertEquals(NfsProbeOutcome.Failed("IOException"), NfsProbeOutcome.of(Result.failure(IOException())))
    }

    @Test
    fun zusammenfassungNutztNormalisiertenPfad() {
        val nfs = NfsSettings(enabled = true, server = "nas.local", export = "/volume1/downloads/", subDir = "jd")
        assertEquals("/volume1/downloads/jd", nfs.rootPath)
    }
}
