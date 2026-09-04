package com.jdandroid

import com.jdandroid.core.FreeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine Logik des Free-Modus: Wartezeit-Anzeige, retryAt, Captcha-Erkennung. */
class FreeModeTest {

    @Test
    fun wartezeitAlsMinutenUndSekunden() {
        assertEquals("00:00", FreeMode.formatWait(0))
        assertEquals("00:05", FreeMode.formatWait(5))
        assertEquals("01:00", FreeMode.formatWait(60))
        assertEquals("12:34", FreeMode.formatWait(12 * 60 + 34))
        assertEquals("59:59", FreeMode.formatWait(3599))
    }

    @Test
    fun wartezeitAbEinerStundeMitStunden() {
        assertEquals("1:00:00", FreeMode.formatWait(3600))
        assertEquals("2:05:09", FreeMode.formatWait(2 * 3600 + 5 * 60 + 9))
    }

    @Test
    fun negativeWartezeitZaehltAlsNull() {
        assertEquals("00:00", FreeMode.formatWait(-30))
    }

    @Test
    fun meldungTraegtPraefix() {
        val message = FreeMode.waitMessage(90)
        assertEquals("Wartezeit im Free-Modus: 01:30", message)
        assertTrue(FreeMode.isWaitMessage(message))
        assertFalse(FreeMode.isWaitMessage("Server antwortete mit HTTP 503"))
        assertFalse(FreeMode.isWaitMessage(null))
    }

    @Test
    fun grundDerWartezeitBleibtInDerMeldungUndBeimHerunterzaehlen() {
        val message = FreeMode.waitMessage(3 * 3600, "Rapidgator: Tageslimit im Free-Modus erreicht")
        assertEquals("Wartezeit im Free-Modus: 3:00:00 – Rapidgator: Tageslimit im Free-Modus erreicht", message)
        assertTrue(FreeMode.isWaitMessage(message))
        assertEquals("Rapidgator: Tageslimit im Free-Modus erreicht", FreeMode.waitReason(message))
        // Die Liste zaehlt herunter und haengt den Grund wieder an
        assertEquals(
            "Wartezeit im Free-Modus: 2:59:00 – Rapidgator: Tageslimit im Free-Modus erreicht",
            FreeMode.waitMessage(3 * 3600 - 60, FreeMode.waitReason(message))
        )
        // Ein Grund mit eigenem Gedankenstrich bleibt ganz erhalten
        val ip = FreeMode.waitMessage(3601, "1fichier: IP-Adresse gesperrt – Freigabe in einer Stunde")
        assertEquals("1fichier: IP-Adresse gesperrt – Freigabe in einer Stunde", FreeMode.waitReason(ip))
        // Ohne Grund nur der Countdown
        assertEquals("Wartezeit im Free-Modus: 01:30", FreeMode.waitMessage(90, null))
        assertEquals("Wartezeit im Free-Modus: 01:30", FreeMode.waitMessage(90, "  "))
        assertNull(FreeMode.waitReason(FreeMode.waitMessage(90)))
        assertNull(FreeMode.waitReason("Fehler – Versuch 2/5 in 20s"))
        assertNull(FreeMode.waitReason(null))
    }

    @Test
    fun captchaMeldungNenntDenGrundUndDenMenuepunkt() {
        val message = FreeMode.captchaMessage("1fichier: Datei ist passwortgeschützt – Passwort im Browser eingeben")
        assertEquals(
            "1fichier: Datei ist passwortgeschützt – Passwort im Browser eingeben – im Menü \"Captcha lösen\"",
            message
        )
        assertTrue(FreeMode.isCaptchaHold(message, 0, 0))
        assertEquals(FreeMode.CAPTCHA_MESSAGE, FreeMode.captchaMessage(null))
        assertEquals(FreeMode.CAPTCHA_MESSAGE, FreeMode.captchaMessage(""))
        assertTrue(FreeMode.isCaptchaHold(FreeMode.CAPTCHA_MESSAGE, 0, 0))
        // Ein Hoster-Text ohne Hinweis ist keine Captcha-Meldung
        assertFalse(FreeMode.isCaptchaHold("Rapidgator: Captcha (Turnstile) – nur im Browser lösbar", 0, 0))
    }

    @Test
    fun retryAtLiegtSekundenInDerZukunft() {
        val now = 1_000_000L
        assertEquals(now + 45_000, FreeMode.retryAt(now, 45))
        // Mindestens eine Sekunde, sonst wuerde pump() den Eintrag sofort erneut starten
        assertEquals(now + 1_000, FreeMode.retryAt(now, 0))
        assertEquals(now + 1_000, FreeMode.retryAt(now, -5))
    }

    @Test
    fun verbleibendeSekundenAufgerundetUndNieNegativ() {
        val now = 10_000L
        assertEquals(0, FreeMode.remainingSeconds(now, now))
        assertEquals(0, FreeMode.remainingSeconds(now - 5_000, now))
        assertEquals(1, FreeMode.remainingSeconds(now + 1, now))
        assertEquals(3, FreeMode.remainingSeconds(now + 2_500, now))
        assertEquals(60, FreeMode.remainingSeconds(now + 60_000, now))
    }

    @Test
    fun captchaEintragErkanntAnMeldungOderFernemRetryAt() {
        val now = 5_000_000L
        assertTrue(FreeMode.isCaptchaHold(FreeMode.CAPTCHA_MESSAGE, 0, now))
        assertTrue(FreeMode.isCaptchaHold(null, now + FreeMode.CAPTCHA_HOLD_MS, now))
        // Normale Wartezeit oder Backoff ist kein Captcha
        assertFalse(FreeMode.isCaptchaHold(FreeMode.waitMessage(120), now + 120_000, now))
        assertFalse(FreeMode.isCaptchaHold("Fehler – Versuch 2/5 in 20s", now + 20_000, now))
        assertFalse(FreeMode.isCaptchaHold(null, 0, now))
    }

    @Test
    fun captchaHaltLiegtJenseitsDesDienstHorizonts() {
        // Sonst hielte ein Captcha-Eintrag den Vordergrunddienst dauerhaft am Leben
        assertTrue(FreeMode.CAPTCHA_HOLD_MS > FreeMode.USER_ACTION_HORIZON_MS)
    }
}
