package com.jdandroid.core

import java.util.Locale

/**
 * Free mode (downloads without an account): wait times and captcha holds as
 * stored codes plus retryAt, and the translated display derived from them.
 * `DownloadItem.errorMessage` never holds translated text, only a code
 * ([WAIT_CODE], [CAPTCHA_CODE]) optionally followed by [REASON_SEPARATOR]
 * and the hoster's reason; [displayText] renders it on read.
 */
object FreeMode {
    /** Entry sitting out a hoster wait time (retryAt = end). */
    const val WAIT_CODE = "FREE_WAIT"

    /** Entry waiting for a captcha in the browser (retryAt beyond the horizon). */
    const val CAPTCHA_CODE = "FREE_CAPTCHA"

    private const val REASON_SEPARATOR = "|"

    /** retryAt offset of a captcha entry: far enough that pump() never starts it by itself. */
    const val CAPTCHA_HOLD_MS = 365L * 24 * 60 * 60 * 1000

    /** A retryAt this far ahead means "waiting for user action"; such entries do not keep the service alive. */
    const val USER_ACTION_HORIZON_MS = 30L * 24 * 60 * 60 * 1000

    fun disabledMessage(): String = Texts.t("engine_free_disabled")

    fun noPremiumMessage(): String = Texts.t("engine_free_no_premium")

    /** "mm:ss", from one hour "h:mm:ss"; negative values count as 0. */
    fun formatWait(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    /** Stored wait note: [WAIT_CODE], with a hoster reason as `FREE_WAIT|reason`; the remaining time lives in retryAt. */
    fun waitNote(reason: String? = null): String = note(WAIT_CODE, reason)

    /** Stored captcha note: [CAPTCHA_CODE], with a reason as `FREE_CAPTCHA|reason`. */
    fun captchaNote(reason: String? = null): String = note(CAPTCHA_CODE, reason)

    private fun note(code: String, reason: String?): String {
        val r = reason?.trim().orEmpty()
        return if (r.isEmpty()) code else code + REASON_SEPARATOR + r
    }

    /** Reason of a wait note, null without one or for a foreign note. */
    fun waitReason(message: String?): String? = reason(message, WAIT_CODE)

    /** Reason of a captcha note, null without one or for a foreign note. */
    fun captchaReason(message: String?): String? = reason(message, CAPTCHA_CODE)

    private fun reason(message: String?, code: String): String? {
        if (message == null || !message.startsWith(code)) return null
        val rest = message.removePrefix(code)
        if (!rest.startsWith(REASON_SEPARATOR)) return null
        return rest.removePrefix(REASON_SEPARATOR).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Translated display text for a stored note (remaining time until
     * [retryAt], captcha hint, reason). Null when [message] is not a free-mode
     * note; the caller then shows the text unchanged.
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

    /** Next attempt time; a wait below 1 s counts as 1 s. */
    fun retryAt(now: Long, seconds: Int): Long = now + seconds.coerceAtLeast(1) * 1000L

    /** Seconds until [retryAt], rounded up, never negative. */
    fun remainingSeconds(retryAt: Long, now: Long): Int =
        if (retryAt <= now) 0 else ((retryAt - now + 999) / 1000).toInt()

    fun isWaitMessage(message: String?): Boolean = message?.startsWith(WAIT_CODE) == true

    fun isCaptchaMessage(message: String?): Boolean = message?.startsWith(CAPTCHA_CODE) == true

    /** Captcha note, or retryAt beyond the user-action horizon. */
    fun isCaptchaHold(message: String?, retryAt: Long, now: Long): Boolean =
        isCaptchaMessage(message) || retryAt - now > USER_ACTION_HORIZON_MS
}
