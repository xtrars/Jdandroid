package com.jdandroid.core

import java.util.Locale

/**
 * Reine Logik des Free-Modus (Downloads ohne Konto): Wartezeiten als
 * Meldung und retryAt, sowie die Kennzeichnung "wartet auf Captcha".
 * Kennt kein Android, wird von Engine und Oberflaeche gemeinsam genutzt.
 */
object FreeMode {
    const val WAIT_PREFIX = "Wartezeit im Free-Modus: "

    /** Handlungshinweis am Ende jeder Captcha-Meldung; daran erkennt die Liste den Eintrag. */
    const val CAPTCHA_HINT = "im Menü \"Captcha lösen\""

    /** Zeile eines Eintrags, der auf ein Captcha wartet (Status bleibt QUEUED), ohne Grund. */
    const val CAPTCHA_MESSAGE = "Captcha nötig – $CAPTCHA_HINT"

    /** Trennzeichen zwischen Countdown und Grund der Wartezeit. */
    private const val REASON_SEPARATOR = " – "

    /** Fehlendes Konto bei ausgeschaltetem Free-Modus. */
    const val DISABLED_MESSAGE = "Kein Konto und Free-Modus aus"

    /** Konto ohne Premium bei ausgeschaltetem Free-Modus. */
    const val NO_PREMIUM_MESSAGE = "Konto ohne Premium und Free-Modus aus"

    /** retryAt eines Captcha-Eintrags: so weit weg, dass pump() ihn nie von selbst startet. */
    const val CAPTCHA_HOLD_MS = 365L * 24 * 60 * 60 * 1000

    /**
     * Ab diesem Abstand gilt ein retryAt als "wartet auf Nutzeraktion":
     * solche Eintraege halten den Dienst nicht am Leben (echte Wartezeiten
     * liegen weit darunter).
     */
    const val USER_ACTION_HORIZON_MS = 30L * 24 * 60 * 60 * 1000

    /** "mm:ss", ab einer Stunde "h:mm:ss". Negative Werte gelten als 0. */
    fun formatWait(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    /**
     * Meldung "Wartezeit im Free-Modus: mm:ss – Grund". Der Grund kommt vom
     * Hoster ("Tageslimit erreicht", "IP-Adresse gesperrt") und bleibt beim
     * Herunterzaehlen erhalten ([waitReason]); ohne Grund nur der Countdown.
     */
    fun waitMessage(seconds: Int, reason: String? = null): String {
        val r = reason?.trim().orEmpty()
        return WAIT_PREFIX + formatWait(seconds) + if (r.isEmpty()) "" else REASON_SEPARATOR + r
    }

    /** Grund aus einer Wartezeit-Meldung, null ohne Grund oder bei fremder Meldung. */
    fun waitReason(message: String?): String? {
        if (!isWaitMessage(message)) return null
        val rest = message!!.removePrefix(WAIT_PREFIX)
        val at = rest.indexOf(REASON_SEPARATOR)
        return if (at < 0) null else rest.substring(at + REASON_SEPARATOR.length).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Meldung eines Captcha-Eintrags: der Grund des Hosters ("Datei ist
     * passwortgeschützt – Passwort im Browser eingeben") plus der Hinweis auf
     * den Menuepunkt; ohne Grund [CAPTCHA_MESSAGE].
     */
    fun captchaMessage(reason: String?): String {
        val r = reason?.trim().orEmpty()
        return if (r.isEmpty()) CAPTCHA_MESSAGE else "$r$REASON_SEPARATOR$CAPTCHA_HINT"
    }

    /** Zeitpunkt des naechsten Versuchs; eine Wartezeit unter 1 s zaehlt als 1 s. */
    fun retryAt(now: Long, seconds: Int): Long = now + seconds.coerceAtLeast(1) * 1000L

    /** Verbleibende Sekunden bis [retryAt], aufgerundet, nie negativ. */
    fun remainingSeconds(retryAt: Long, now: Long): Int =
        if (retryAt <= now) 0 else ((retryAt - now + 999) / 1000).toInt()

    /** Ist [message] eine Wartezeit-Meldung dieses Modus? */
    fun isWaitMessage(message: String?): Boolean = message?.startsWith(WAIT_PREFIX) == true

    /** Eintrag wartet auf ein Captcha (Meldung endet mit dem Hinweis, oder retryAt jenseits des Horizonts). */
    fun isCaptchaHold(message: String?, retryAt: Long, now: Long): Boolean =
        message?.endsWith(CAPTCHA_HINT) == true || retryAt - now > USER_ACTION_HORIZON_MS
}
