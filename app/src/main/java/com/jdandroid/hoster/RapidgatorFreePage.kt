package com.jdandroid.hoster

import com.jdandroid.core.Texts

/**
 * Classification of a notice text of the Rapidgator website in free mode
 * (file page or "code" of the Ajax replies).
 */
internal sealed class RapidgatorBlock {
    /** Wait in seconds (including margin) with a translated message (Texts.t). */
    data class Wait(val seconds: Int, val text: String) : RapidgatorBlock()

    /** Not downloadable without premium; retrying is pointless. */
    data class Permanent(val text: String) : RapidgatorBlock()

    /** Timer was not accepted: restart the flow. */
    object Restart : RapidgatorBlock()
}

/** The JavaScript variables of the file page that drive the free flow. */
internal data class RapidgatorFreeVars(
    val fid: String,
    val secs: Int,
    val startTimerUrl: String,
    val getDownloadUrl: String,
    val captchaUrl: String
)

/**
 * Pure parsing of the Rapidgator file page and the Ajax replies in free mode
 * (no network, no Android), so every rule stays testable against real page
 * snippets.
 *
 * Layout (as of 09/2026): name in `Downloading: </strong><a href="">…</a>`,
 * size in `File size: <strong>1 GB</strong>`, the flow as JavaScript
 * variables (`var secs = 180; var fid = …; var startTimerUrl =
 * '/download/AjaxStartTimer'`). `AjaxStartTimer` answers
 * `{"state":"started","sid":"…"}`, `AjaxGetDownloadLink` `{"state":"done"}`;
 * errors come as `{"state":"error","code":"…"}`.
 */
internal object RapidgatorFreePage {

    private val ic = RegexOption.IGNORE_CASE

    /** File id (32 hex or number) and optional name segment from the link. */
    fun fileIdAndName(url: String): Pair<String, String?>? =
        Regex("""/file/([a-f0-9]{32}|\d+)(?:/([^/?#\s]+\.html))?""", ic).find(url)
            ?.let { it.groupValues[1] to it.groupValues[2].ifBlank { null } }

    fun fileName(html: String): String? =
        Regex("""Downloading:\s*</strong>\s*<a href=""[^>]*>\s*([^<]+?)\s*</a>""", ic)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("""<title>\s*Download file\s+([^<]+?)\s*</title>""", ic)
                .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Size in bytes (1024-based), -1 if not stated. */
    fun fileSize(html: String): Long {
        val m = Regex("""File size:\s*<strong>\s*([\d.,]+)\s*([KMGT]?B)\s*</strong>""", ic).find(html)
            ?: return -1
        return toBytes(m.groupValues[1], m.groupValues[2])
    }

    fun toBytes(value: String, unit: String): Long {
        val number = value.replace(",", ".").toDoubleOrNull() ?: return -1
        val factor = when (unit.uppercase().first()) {
            'K' -> 1024.0
            'M' -> 1024.0 * 1024
            'G' -> 1024.0 * 1024 * 1024
            'T' -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (number * factor).toLong()
    }

    /** MD5 of the file if the page shows one (usually not on the free page). */
    fun md5(html: String): String? =
        Regex(""">\s*MD5\s*:\s*([A-Fa-f0-9]{32})<""").find(html)?.groupValues?.get(1)?.lowercase()

    private fun jsVar(html: String, name: String): String? =
        Regex("""var\s+$name\s*=\s*'?([^';]*)'?\s*;""").find(html)?.groupValues?.get(1)?.trim()

    /** null if the page offers no free flow (no fid / no timer address). */
    fun freeVars(html: String): RapidgatorFreeVars? {
        val fid = jsVar(html, "fid")?.takeIf { it.isNotEmpty() && it != "0" } ?: return null
        val start = jsVar(html, "startTimerUrl")?.takeIf { it.isNotEmpty() } ?: return null
        val get = jsVar(html, "getDownloadUrl")?.takeIf { it.isNotEmpty() } ?: return null
        val captcha = jsVar(html, "captchaUrl")?.takeIf { it.isNotEmpty() } ?: "/download/captcha"
        val secs = jsVar(html, "secs")?.toIntOrNull() ?: 180
        return RapidgatorFreeVars(fid, secs.coerceAtLeast(0), start, get, captcha)
    }

    fun isOffline(html: String): Boolean =
        Regex(""">\s*(?:404 File not found|Error 404|File not found)\s*<""", ic).containsMatchIn(html)

    /** Page contains the captcha form (Turnstile or legacy). */
    fun hasCaptchaForm(html: String): Boolean =
        Regex("""id=["']captchaform["']|DownloadCaptchaForm\[verifyCode]""", ic).containsMatchIn(html)

    /** Reply of AjaxStartTimer / AjaxGetDownloadLink. */
    data class AjaxReply(val state: String, val sid: String?, val code: String?)

    /**
     * The Ajax replies are flat JSON objects of strings
     * (`{"state":"error","code":"You didn`t wait …","0":"step3"}`); a simple
     * field grab suffices and stays testable without a JSON library.
     */
    fun ajaxReply(json: String): AjaxReply? {
        fun field(name: String): String? =
            Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)?.let(::unescape)
        val state = field("state") ?: return null
        return AjaxReply(state, field("sid")?.takeIf { it.isNotEmpty() }, field("code")?.takeIf { it.isNotEmpty() })
    }

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")

    /**
     * Direct link from the page after the solved captcha
     * (`https://pr5.rapidgator.net//?r=download/index&session_id=…`).
     */
    fun directLink(html: String): String? =
        Regex("""['"](https?://[A-Za-z0-9_-]+\.[^/'"]+//\?r=download/index&(?:amp;)?session_id=[A-Za-z0-9]+)['"]""")
            .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: Regex("""return\s+'(https?://[A-Za-z0-9_-]+\.rapidgator\.net/[^']*)';""").find(html)?.groupValues?.get(1)

    /**
     * Website notice texts. Order: the specific patterns with a time first,
     * then the generic limits; premium limits are permanent. The fixed table
     * row "1 file per 120 minutes" is not an error.
     */
    fun classify(text: String): RapidgatorBlock? {
        val t = text.replace('`', '\'').replace("&#039;", "'")
        Regex("""You can download files up to\s*([\d.,]+\s*[KMGT]?B)""", ic).find(t)?.let {
            return RapidgatorBlock.Permanent(Texts.t("hoster_rapidgator_free_size_limit", it.groupValues[1].trim()))
        }
        if (Regex("""can be downloaded by premium only""", ic).containsMatchIn(t)) {
            return RapidgatorBlock.Permanent(Texts.t("hoster_rapidgator_premium_only"))
        }
        if (Regex("""can be downloaded only by subscribers""", ic).containsMatchIn(t)) {
            return RapidgatorBlock.Permanent(Texts.t("hoster_rapidgator_subscribers_only"))
        }
        if (Regex("""didn'?t wait specified time""", ic).containsMatchIn(t)) return RapidgatorBlock.Restart
        Regex("""Delay between downloads must be not less than\s*(\d+)\s*min""", ic).find(t)?.let {
            val minutes = it.groupValues[1].toInt()
            return RapidgatorBlock.Wait(minutes * 60 + 1, Texts.t("hoster_rapidgator_next_free_in", HosterDurations.text(minutes * 60)))
        }
        Regex("""Try again in\s*(\d+)\s*(second|sec|minute|min|hour)s?""", ic).find(t)?.let {
            val n = it.groupValues[1].toInt()
            val secs = when (it.groupValues[2].lowercase().first()) {
                'h' -> n * 3600
                'm' -> n * 60
                else -> n
            }
            return RapidgatorBlock.Wait(secs + 1, Texts.t("hoster_rapidgator_locked_retry_in", HosterDurations.text(secs)))
        }
        if (Regex("""reached your daily (?:downloads )?limit""", ic).containsMatchIn(t)) {
            return RapidgatorBlock.Wait(3 * 3600, Texts.t("hoster_rapidgator_daily_limit"))
        }
        if (Regex("""reached your hourly (?:downloads )?limit""", ic).containsMatchIn(t)) {
            return RapidgatorBlock.Wait(3600, Texts.t("hoster_rapidgator_hourly_limit"))
        }
        if (Regex("""download (?:not )?more than 1 file at a time|File is already downloading""", ic).containsMatchIn(t)) {
            return RapidgatorBlock.Wait(60, Texts.t("hoster_rapidgator_one_at_a_time"))
        }
        if (Regex("""File is temporarily (?:not |un)?available""", ic).containsMatchIn(t)) {
            return RapidgatorBlock.Wait(5 * 60, Texts.t("hoster_rapidgator_temporarily_unavailable"))
        }
        if (Regex("""Downloading is not possible at the moment""", ic).containsMatchIn(t)) {
            return RapidgatorBlock.Wait(30 * 60, Texts.t("hoster_rapidgator_download_not_possible"))
        }
        return null
    }

    /**
     * Free block on the file page: only the visible text counts, so the
     * patterns do not match inside scripts or comments.
     */
    fun pageBlock(html: String): RapidgatorBlock? = classify(visibleText(html))

    fun visibleText(html: String): String =
        html.replace(Regex("""<script[\s\S]*?</script>""", ic), " ")
            .replace(Regex("""<style[\s\S]*?</style>""", ic), " ")
            .replace(Regex("""<!--[\s\S]*?-->"""), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""\s+"""), " ")
}
