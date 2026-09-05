package com.jdandroid

import com.jdandroid.core.FreeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun vermerkTraegtDenCode() {
        val note = FreeMode.waitNote()
        assertEquals("FREE_WAIT", note)
        assertTrue(FreeMode.isWaitMessage(note))
        assertFalse(FreeMode.isWaitMessage("Server antwortete mit HTTP 503"))
        assertFalse(FreeMode.isWaitMessage(null))
    }

    @Test
    fun grundDerWartezeitBleibtImVermerkUndInDerAnzeige() {
        val note = FreeMode.waitNote("Rapidgator: Tageslimit im Free-Modus erreicht")
        assertEquals("FREE_WAIT|Rapidgator: Tageslimit im Free-Modus erreicht", note)
        assertTrue(FreeMode.isWaitMessage(note))
        assertEquals("Rapidgator: Tageslimit im Free-Modus erreicht", FreeMode.waitReason(note))
        // Display counts down from retryAt and appends the reason
        val now = 5_000_000L
        assertEquals(
            "Wartezeit im Free-Modus: 2:59:00 – Rapidgator: Tageslimit im Free-Modus erreicht",
            FreeMode.displayText(note, now + (3 * 3600 - 60) * 1000L, now)
        )
        // A reason containing a dash is kept whole
        val ip = FreeMode.waitNote("1fichier: IP-Adresse gesperrt – Freigabe in einer Stunde")
        assertEquals("1fichier: IP-Adresse gesperrt – Freigabe in einer Stunde", FreeMode.waitReason(ip))
        // Without a reason only the countdown
        assertEquals("FREE_WAIT", FreeMode.waitNote(null))
        assertEquals("FREE_WAIT", FreeMode.waitNote("  "))
        assertEquals("Wartezeit im Free-Modus: 01:30", FreeMode.displayText(FreeMode.waitNote(), now + 90_000, now))
        assertNull(FreeMode.waitReason(FreeMode.waitNote()))
        assertNull(FreeMode.waitReason("Fehler – Versuch 2/5 in 20s"))
        assertNull(FreeMode.waitReason(null))
        // Foreign notes get no display text
        assertNull(FreeMode.displayText("Fehler – Versuch 2/5 in 20s", now + 20_000, now))
        assertNull(FreeMode.displayText(null, 0, now))
    }

    @Test
    fun captchaVermerkNenntDenGrundUndDieAnzeigeDenMenuepunkt() {
        val note = FreeMode.captchaNote("1fichier: Datei ist passwortgeschützt – Passwort im Browser eingeben")
        assertEquals("FREE_CAPTCHA|1fichier: Datei ist passwortgeschützt – Passwort im Browser eingeben", note)
        assertEquals(
            "1fichier: Datei ist passwortgeschützt – Passwort im Browser eingeben – im Menü „Captcha lösen“",
            FreeMode.displayText(note, 0, 0)
        )
        assertTrue(FreeMode.isCaptchaHold(note, 0, 0))
        assertEquals("FREE_CAPTCHA", FreeMode.captchaNote(null))
        assertEquals("FREE_CAPTCHA", FreeMode.captchaNote(""))
        assertEquals("Captcha nötig – im Menü „Captcha lösen“", FreeMode.displayText(FreeMode.captchaNote(), 0, 0))
        assertNull(FreeMode.captchaReason(FreeMode.captchaNote()))
        assertTrue(FreeMode.isCaptchaHold(FreeMode.captchaNote(), 0, 0))
        // A hoster text without code is not a captcha note
        assertFalse(FreeMode.isCaptchaHold("Rapidgator: Captcha (Turnstile) – nur im Browser lösbar", 0, 0))
    }

    @Test
    fun retryAtLiegtSekundenInDerZukunft() {
        val now = 1_000_000L
        assertEquals(now + 45_000, FreeMode.retryAt(now, 45))
        // At least one second, otherwise pump() would restart the entry at once
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
        assertTrue(FreeMode.isCaptchaHold(FreeMode.captchaNote(), 0, now))
        assertTrue(FreeMode.isCaptchaHold(null, now + FreeMode.CAPTCHA_HOLD_MS, now))
        // Ordinary wait or backoff is not a captcha
        assertFalse(FreeMode.isCaptchaHold(FreeMode.waitNote(), now + 120_000, now))
        assertFalse(FreeMode.isCaptchaHold("Fehler – Versuch 2/5 in 20s", now + 20_000, now))
        assertFalse(FreeMode.isCaptchaHold(null, 0, now))
    }

    @Test
    fun captchaHaltLiegtJenseitsDesDienstHorizonts() {
        // Otherwise a captcha entry would keep the foreground service alive forever
        assertTrue(FreeMode.CAPTCHA_HOLD_MS > FreeMode.USER_ACTION_HORIZON_MS)
    }
}
