package com.jdandroid

import com.jdandroid.hoster.OneFichierHoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Das CDN-Guthaben von 1fichier steht im Kontostatus in 1024-basierten Einheiten. */
class OneFichierAccountStatusTest {

    private val hoster = OneFichierHoster()

    @Test
    fun cdnGuthabenIn1024Einheiten() {
        val status = hoster.withCdnCredit("Premium/Access", 1.5)
        assertTrue(status, status.startsWith("Premium/Access · CDN-Guthaben 1"))
        assertTrue(status, status.endsWith(" GiB"))
        assertFalse(status, status.endsWith(" GB"))
    }

    @Test
    fun grossesGuthabenWirdTiB() {
        assertTrue(hoster.withCdnCredit("Premium/Access", 2048.0).endsWith(" TiB"))
    }

    @Test
    fun ohneGuthabenBleibtDerStatus() {
        assertEquals("Premium/Access", hoster.withCdnCredit("Premium/Access", null))
        assertEquals("Free", hoster.withCdnCredit("Free", 0.0))
    }
}
