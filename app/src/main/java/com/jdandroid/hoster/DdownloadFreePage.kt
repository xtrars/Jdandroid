package com.jdandroid.hoster

import com.jdandroid.core.Texts

/** Captcha type on an XFileSharing file page in free mode. */
internal sealed class FreeCaptcha {
    /** No captcha in the download form. */
    object None : FreeCaptcha()

    /** Cloudflare Turnstile, reCAPTCHA or hCaptcha: only solvable in the browser. */
    data class Browser(val kind: String) : FreeCaptcha()

    /** Image captcha (field "code"): the user has to type it, so browser. */
    data class Image(val url: String) : FreeCaptcha()

    /**
     * XFS span captcha: four digits in spans whose order is only defined by
     * padding-left. Solvable without the user; [code] is the solution.
     */
    data class Span(val code: String) : FreeCaptcha()
}

/**
 * Pure parsing of the ddownload file page in free mode (no network, no
 * Android), so every rule stays testable against real page snippets.
 *
 * Page layout (as of 09/2026): form errors in `<div class="dk-dl-alert">…</div>`,
 * blocks as a tooltip on the locked button (`data-toast-msg`,
 * `data-wait-seconds`), the pre-download countdown in
 * `<span class="dk-countdown-num">60</span>`. The classic XFS patterns
 * (countdown_str, "You have to wait …") are kept in case the layout changes back.
 */
internal object DdownloadFreePage {

    private val ic = RegexOption.IGNORE_CASE

    /** Form error ("Wrong captcha", "Expired download session"). */
    fun alert(html: String): String? =
        Regex("""class=["']dk-dl-alert["'][^>]*>\s*([^<]+?)\s*<""", ic)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Tooltip on the locked button (block, size limit, form error). */
    fun toast(html: String): String? =
        Regex("""data-toast-msg=["']([^"']*)["']""", ic)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Both error texts of the page for text checks; empty if none. */
    private fun errorTexts(html: String): String =
        listOfNotNull(alert(html), toast(html)).joinToString(" | ")

    /**
     * Block (IP/daily limit) in seconds, 0 = none. First the raw field
     * `data-wait-seconds`, otherwise the classic sentence "You have reached the
     * download-limit / You have to wait 1 hour, 23 minutes, 5 seconds", also in
     * digital form "1:23:05".
     */
    fun waitSeconds(html: String): Int {
        Regex("""data-wait-seconds=["'](\d+)["']""", ic).find(html)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val sentence = Regex(
            """(You have reached the download[- ]limit|You have to wait|Du musst noch)([^<>"']+)""", ic
        ).find(html)?.groupValues?.get(2) ?: return 0
        return parseWaitText(sentence)
    }

    /** "1 hour, 23 minutes, 5 seconds", "2 days", "1:23:05" or "23:05" → seconds; 0 = nothing found. */
    fun parseWaitText(text: String): Int {
        fun unit(name: String): Int =
            Regex("""(\d+)\s*(?:$name)""", ic).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val days = unit("days?")
        val hours = unit("hours?|stunden?")
        val minutes = unit("minutes?|minuten?")
        val seconds = unit("seconds?|sekunden?")
        val fromUnits = days * 86_400 + hours * 3600 + minutes * 60 + seconds
        if (fromUnits > 0) return fromUnits
        Regex("""\b(\d{1,3}):(\d{2}):(\d{2})\b""").find(text)?.let { m ->
            val (h, mi, s) = m.destructured
            return h.toInt() * 3600 + mi.toInt() * 60 + s.toInt()
        }
        Regex("""\b(\d{1,3}):(\d{2})\b""").find(text)?.let { m ->
            val (mi, s) = m.destructured
            return mi.toInt() * 60 + s.toInt()
        }
        return 0
    }

    /**
     * Client-side countdown before the download (currently 60 s), 0 = none.
     * Current layout `dk-countdown-num`, classic `countdown_str` or `class="seconds"`.
     */
    fun countdownSeconds(html: String): Int {
        val patterns = listOf(
            """class=["'][^"']*\bdk-countdown-num\b[^"']*["'][^>]*>\s*(\d+)\s*<""",
            """id=["']countdown_str["'][^>]*>[^<]*<span[^>]*>\s*(\d+)\s*</span>""",
            """class=["']seconds["'][^>]*>\s*(\d+)\s*<"""
        )
        for (p in patterns) {
            Regex(p, ic).find(html)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return 0
    }

    /**
     * Captcha type of the download form. Turnstile also appears in scripts for
     * the guest purchase on the file page; what counts is the widget
     * (`class="cf-turnstile"`) or a response field in the form.
     */
    fun captcha(html: String): FreeCaptcha {
        solveSpanCaptcha(html)?.let { return FreeCaptcha.Span(it) }
        Regex("""(https?://[^"'\s]+?/captchas?/[^"'\s]+)""", ic).find(html)
            ?.groupValues?.get(1)?.let { return FreeCaptcha.Image(it) }
        val turnstile = Regex("""class=["'][^"']*\bcf-turnstile\b[^"']*["']""", ic).containsMatchIn(html) ||
            Regex("""name=["']cf-turnstile-response["']""", ic).containsMatchIn(html) ||
            Regex("""data-sitekey=["']0x[0-9A-Za-z_-]+["']""").containsMatchIn(html)
        if (turnstile) return FreeCaptcha.Browser("Turnstile")
        if (Regex("""class=["'][^"']*\bg-recaptcha\b|name=["']g-recaptcha-response["']|google\.com/recaptcha""", ic)
                .containsMatchIn(html)
        ) return FreeCaptcha.Browser("reCAPTCHA")
        if (Regex("""class=["'][^"']*\bh-captcha\b|name=["']h-captcha-response["']|hcaptcha\.com""", ic)
                .containsMatchIn(html)
        ) return FreeCaptcha.Browser("hCaptcha")
        return FreeCaptcha.None
    }

    /**
     * Solves the XFS span captcha: the block after "Enter code" holds spans
     * with `padding-left:<px>` and one digit each (raw or as `&#NN;`); the
     * digits sorted by padding-left form the code. null = no such captcha or
     * incomplete (fewer than four digits).
     */
    fun solveSpanCaptcha(html: String): String? {
        val block = Regex(""">\s*Enter code.*?<div[^>]*>(.+?)</div>""", setOf(ic, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: return null
        val digits = Regex("""<span[^>]*padding-left\s*:\s*(\d+)[^>]*>\s*(&#\d+;|\d)\s*</span>""", ic)
            .findAll(block)
            .map { m ->
                val raw = m.groupValues[2]
                val ch = if (raw.startsWith("&#")) raw.removePrefix("&#").removeSuffix(";").toInt().toChar() else raw[0]
                m.groupValues[1].toInt() to ch
            }
            .filter { it.second.isDigit() }
            .sortedBy { it.first }
            .map { it.second }
            .joinToString("")
        return digits.takeIf { it.length >= 4 }
    }

    /**
     * Reason why the file cannot be downloaded without premium (permanent),
     * as a translated message (Texts.t); null = downloadable.
     */
    fun premiumOnlyReason(html: String): String? {
        val text = errorTexts(html) + " " + html
        Regex("""can download files up to\s*([^<>"']*?)\s*only""", ic).find(text)?.let { m ->
            val limit = m.groupValues[1].trim()
            return Texts.t("hoster_ddownload_free_size_limit", limit)
        }
        val premiumOnly = Regex(
            """>\s*Upgrade your account to download (?:larger|bigger) files|""" +
                """reached max downloads limit for Free Users|""" +
                """This file is available for\s*(?:<[^>]*>\s*)?Premium Users only|""" +
                """Available Only for Premium Members|available only for Premium users|""" +
                """Please Buy Premium To download this file|This file reached max downloads limit|""" +
                """This file (?:can|only can|can only) be downloaded by""",
            ic
        ).containsMatchIn(text)
        return if (premiumOnly) Texts.t("hoster_ddownload_premium_only") else null
    }

    /**
     * File gone: known texts, copyright ban or an empty page (title "Download "
     * without a name and without a download form), which is how ddownload
     * answers an invalid code.
     */
    fun isOffline(html: String): Boolean {
        if (html.contains("File Not Found", true) || html.contains("file was deleted", true) ||
            html.contains("No such file", true) || Regex(""">\s*This file was banned by copyright""", ic).containsMatchIn(html)
        ) return true
        val emptyTitle = Regex("""<title>\s*Download\s*</title>""", ic).containsMatchIn(html)
        val hasForm = Regex("""name=["']op["'][^>]*value=["']download\d?["']|value=["']download\d?["'][^>]*name=["']op["']""", ic)
            .containsMatchIn(html)
        return emptyTitle && !hasForm
    }

    fun isMaintenance(html: String): Boolean =
        Regex(""">\s*[\w ]*server (?:is in )?(?:maintenance|maintainance)""", ic).containsMatchIn(html)

    fun isWrongCaptcha(html: String): Boolean =
        Regex("""[>"']\s*Wrong captcha""", ic).containsMatchIn(html)

    fun isExpiredSession(html: String): Boolean =
        Regex("""[>"']\s*Expired download session""", ic).containsMatchIn(html)

    fun isSkippedCountdown(html: String): Boolean =
        Regex("""[>"']\s*Skipped countdown""", ic).containsMatchIn(html)

    /** Block without a concrete time ("download limit", "too many", "try again later"). */
    fun isLimitWithoutTime(html: String): Boolean =
        Regex("""download[- ]limit|limit reached|too many|try again later""", ic).containsMatchIn(errorTexts(html)) ||
            Regex("""You have reached the maximum limit|using all download slots for IP""", ic).containsMatchIn(html)
}
