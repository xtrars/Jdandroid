package com.jdandroid.core

import java.util.Locale

/**
 * Reine Logik des Free-Modus (Downloads ohne Konto): Wartezeiten und
 * Captcha-Halt als gespeicherte Codes plus retryAt, sowie die uebersetzte
 * Anzeige daraus. Kennt kein Android, wird von Engine und Oberflaeche
 * gemeinsam genutzt.
 *
 * In `DownloadItem.errorMessage` steht nie ein uebersetzter Text, sondern
 * ein Code ([WAIT_CODE], [CAPTCHA_CODE]), optional gefolgt von
 * [REASON_SEPARATOR] und dem Grund des Hosters. Der Anzeigetext entsteht
 * erst beim Lesen aus Code, retryAt und Grund ([displayText]).
 */
object FreeMode {
    /** Code eines Eintrags, der eine Wartezeit des Hosters absitzt (retryAt = Ende). */
    const val WAIT_CODE = "FREE_WAIT"

    /** Code eines Eintrags, der auf ein Captcha im Browser wartet (retryAt jenseits des Horizonts). */
    const val CAPTCHA_CODE = "FREE_CAPTCHA"

    /** Trennzeichen zwischen Code und Grund des Hosters. */
    private const val REASON_SEPARATOR = "|"

    /** retryAt eines Captcha-Eintrags: so weit weg, dass pump() ihn nie von selbst startet. */
    const val CAPTCHA_HOLD_MS = 365L * 24 * 60 * 60 * 1000

    /**
     * Ab diesem Abstand gilt ein retryAt als "wartet auf Nutzeraktion":
     * solche Eintraege halten den Dienst nicht am Leben (echte Wartezeiten
     * liegen weit darunter).
     */
    const val USER_ACTION_HORIZON_MS = 30L * 24 * 60 * 60 * 1000

    /** Fehlendes Konto bei ausgeschaltetem Free-Modus (uebersetzt). */
    fun disabledMessage(): String = Texts.t("engine_free_disabled")

    /** Konto ohne Premium bei ausgeschaltetem Free-Modus (uebersetzt). */
    fun noPremiumMessage(): String = Texts.t("engine_free_no_premium")

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
     * Gespeicherter Vermerk einer Wartezeit: [WAIT_CODE], mit Grund des
     * Hosters ("Tageslimit erreicht") als `FREE_WAIT|Grund`. Die Restzeit
     * steht nicht im Vermerk, sondern in retryAt.
     */
    fun waitNote(reason: String? = null): String = note(WAIT_CODE, reason)

    /** Gespeicherter Vermerk eines Captcha-Eintrags: [CAPTCHA_CODE], mit Grund `FREE_CAPTCHA|Grund`. */
    fun captchaNote(reason: String? = null): String = note(CAPTCHA_CODE, reason)

    private fun note(code: String, reason: String?): String {
        val r = reason?.trim().orEmpty()
        return if (r.isEmpty()) code else code + REASON_SEPARATOR + r
    }

    /** Grund aus einem Wartezeit-Vermerk, null ohne Grund oder bei fremdem Vermerk. */
    fun waitReason(message: String?): String? = reason(message, WAIT_CODE)

    /** Grund aus einem Captcha-Vermerk, null ohne Grund oder bei fremdem Vermerk. */
    fun captchaReason(message: String?): String? = reason(message, CAPTCHA_CODE)

    private fun reason(message: String?, code: String): String? {
        if (message == null || !message.startsWith(code)) return null
        val rest = message.removePrefix(code)
        if (!rest.startsWith(REASON_SEPARATOR)) return null
        return rest.removePrefix(REASON_SEPARATOR).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Uebersetzter Anzeigetext zu einem gespeicherten Vermerk: Wartezeit mit
     * Restzeit bis [retryAt] (und Grund), Captcha-Hinweis (und Grund). Null,
     * wenn [message] kein Vermerk dieses Modus ist - der Aufrufer zeigt dann
     * den Text unveraendert.
     */
    fun displayText(message: String?, retryAt: Long, now: Long): String? = when {
        isWaitMessage(message) -> {
            val remaining = formatWait(remainingSeconds(retryAt, now))
            val reason = waitReason(message)
            if (reason == null) Texts.t("engine_free_wait", remaining)
            else Texts.t("engine_free_wait_reason", remaining, reason)
        }
        isCaptchaMessage(message) -> {
            val reason = captchaReason(message)
            if (reason == null) Texts.t("engine_free_captcha") else Texts.t("engine_free_captcha_reason", reason)
        }
        else -> null
    }

    /** Zeitpunkt des naechsten Versuchs; eine Wartezeit unter 1 s zaehlt als 1 s. */
    fun retryAt(now: Long, seconds: Int): Long = now + seconds.coerceAtLeast(1) * 1000L

    /** Verbleibende Sekunden bis [retryAt], aufgerundet, nie negativ. */
    fun remainingSeconds(retryAt: Long, now: Long): Int =
        if (retryAt <= now) 0 else ((retryAt - now + 999) / 1000).toInt()

    /** Ist [message] ein Wartezeit-Vermerk dieses Modus? */
    fun isWaitMessage(message: String?): Boolean = message?.startsWith(WAIT_CODE) == true

    /** Ist [message] ein Captcha-Vermerk dieses Modus? */
    fun isCaptchaMessage(message: String?): Boolean = message?.startsWith(CAPTCHA_CODE) == true

    /** Eintrag wartet auf ein Captcha (Captcha-Vermerk, oder retryAt jenseits des Horizonts). */
    fun isCaptchaHold(message: String?, retryAt: Long, now: Long): Boolean =
        isCaptchaMessage(message) || retryAt - now > USER_ACTION_HORIZON_MS
}
