package com.jdandroid

import com.jdandroid.hoster.OneFichierHoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification of 1fichier status=KO answers: blocks are temporary,
 * missing file and missing Premium/Access permanent.
 */
class OneFichierFailureTest {

    private val hoster = OneFichierHoster()

    @Test
    fun fehlendesPremiumIstPermanent() {
        assertTrue(hoster.koFailure("You must be a Premium/Access user to use this API").permanent)
        assertTrue(hoster.koFailure("Premium account required").permanent)
    }

    @Test
    fun floodSperreIstVoruebergehendAuchMitPremiumImText() {
        assertFalse(hoster.koFailure("Flood detected: IP Locked").permanent)
        assertFalse(hoster.koFailure("Premium users: please try again in a few minutes").permanent)
        assertFalse(hoster.koFailure(null).permanent)
        assertFalse(hoster.koFailure("Unerwartet").permanent)
    }

    @Test
    fun fehlendeDateiIstPermanent() {
        assertTrue(hoster.koFailure("Resource not found").permanent)
        assertTrue(hoster.koFailure("File deleted").permanent)
    }

    @Test
    fun nichtAngemeldetIstPermanent() {
        assertTrue(hoster.koFailure("Not authenticated").permanent)
    }
}
