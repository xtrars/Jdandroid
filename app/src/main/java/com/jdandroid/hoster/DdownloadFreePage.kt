package com.jdandroid.hoster

/**
 * Captcha-Art auf einer XFileSharing-Dateiseite im Free-Modus.
 */
internal sealed class FreeCaptcha {
    /** Kein Captcha im Download-Formular. */
    object None : FreeCaptcha()

    /** Cloudflare Turnstile, reCAPTCHA oder hCaptcha: nur im Browser loesbar. */
    data class Browser(val kind: String) : FreeCaptcha()

    /** Bild-Captcha (Feld "code"): der Nutzer muss es abtippen, also Browser. */
    data class Image(val url: String) : FreeCaptcha()

    /**
     * XFS-Span-Captcha: vier Ziffern in Spans, deren Reihenfolge nur ueber
     * padding-left festliegt. Ohne Nutzer loesbar; [code] ist die Loesung.
     */
    data class Span(val code: String) : FreeCaptcha()
}

/**
 * Reine Auswertung der ddownload-Dateiseite im Free-Modus (ohne Netz, ohne
 * Android), damit jede Regel gegen echte Seitenausschnitte pruefbar bleibt.
 *
 * Aufbau der Seite (Stand 09/2026): Formularfehler stehen in
 * `<div class="dk-dl-alert">…</div>`, Sperren als Sprechblase am gesperrten
 * Knopf (`data-toast-msg`, `data-wait-seconds`), der Countdown vor dem
 * Download in `<span class="dk-countdown-num">60</span>`. Daneben die
 * klassischen XFS-Muster (countdown_str, "You have to wait …"), falls der
 * Anbieter das Layout wieder wechselt.
 */
internal object DdownloadFreePage {

    private val ic = RegexOption.IGNORE_CASE

    /** Formularfehler ("Wrong captcha", "Expired download session"). */
    fun alert(html: String): String? =
        Regex("""class=["']dk-dl-alert["'][^>]*>\s*([^<]+?)\s*<""", ic)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Sprechblase am gesperrten Knopf (Sperre, Groessengrenze, Formularfehler). */
    fun toast(html: String): String? =
        Regex("""data-toast-msg=["']([^"']*)["']""", ic)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Beide Fehlertexte der Seite fuer Textpruefungen, leer wenn keiner da ist. */
    private fun errorTexts(html: String): String =
        listOfNotNull(alert(html), toast(html)).joinToString(" | ")

    /**
     * Sperre (IP-/Tageslimit) in Sekunden, 0 = keine. Zuerst das Rohfeld
     * `data-wait-seconds`, sonst der klassische Satz "You have reached the
     * download-limit / You have to wait 1 hour, 23 minutes, 5 seconds",
     * auch mit Digitalformat "1:23:05".
     */
    fun waitSeconds(html: String): Int {
        Regex("""data-wait-seconds=["'](\d+)["']""", ic).find(html)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val sentence = Regex(
            """(You have reached the download[- ]limit|You have to wait|Du musst noch)([^<>"']+)""", ic
        ).find(html)?.groupValues?.get(2) ?: return 0
        return parseWaitText(sentence)
    }

    /** "1 hour, 23 minutes, 5 seconds", "2 days", "1:23:05" oder "23:05" → Sekunden; 0 = nichts gefunden. */
    fun parseWaitText(text: String): Int {
        fun unit(name: String): Int =
            Regex("""(\d+)\s*$name""", ic).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
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
     * Clientseitiger Countdown vor dem Download (aktuell 60 s), 0 = keiner.
     * Aktuelles Layout `dk-countdown-num`, klassisch `countdown_str` bzw.
     * `class="seconds"`.
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
     * Captcha-Art des Download-Formulars. Turnstile steht auf der
     * Dateiseite auch in Skripten fuer den Gast-Kauf; massgeblich ist das
     * Widget (`class="cf-turnstile"`) oder ein Antwortfeld im Formular.
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
     * XFS-Span-Captcha loesen: Block hinter "Enter code", darin Spans mit
     * `padding-left:<px>` und einer Ziffer (roh oder als `&#NN;`). Die
     * Ziffern nach padding-left sortiert ergeben den Code. null = kein
     * solches Captcha oder unvollstaendig (weniger als vier Ziffern).
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
     * Grund, warum die Datei ohne Premium nicht ladbar ist (dauerhaft), als
     * deutsche Meldung; null = frei ladbar.
     */
    fun premiumOnlyReason(html: String): String? {
        val text = errorTexts(html) + " " + html
        Regex("""can download files up to\s*([^<>"']*?)\s*only""", ic).find(text)?.let { m ->
            val limit = m.groupValues[1].trim()
            return "Free-Download nur bis $limit – für diese Datei ist ein Premium-Konto nötig"
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
        return if (premiumOnly) "Datei ist nur mit Premium-Konto ladbar" else null
    }

    /**
     * Datei weg: bekannte Texte, Copyright-Sperre oder eine leere Seite
     * (Titel "Download " ohne Namen und ohne Download-Formular) - so
     * antwortet ddownload auf einen ungueltigen Code.
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

    /** Sperre ohne konkrete Zeit ("download limit", "too many", "try again later"). */
    fun isLimitWithoutTime(html: String): Boolean =
        Regex("""download[- ]limit|limit reached|too many|try again later""", ic).containsMatchIn(errorTexts(html)) ||
            Regex("""You have reached the maximum limit|using all download slots for IP""", ic).containsMatchIn(html)
}
