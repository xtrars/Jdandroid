package com.jdandroid.hoster

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure parsing of the ddownload account page and the API account values (no
 * network, no Android): expiry date, premium detection, quota with plausibility
 * limit, API key. Keeps every rule testable against real page snippets.
 */
internal object DdownloadAccountPage {

    /** Daily quota of ddownload Premium as stated by the provider (200 GB). */
    val DAILY_QUOTA = 200L shl 30

    /**
     * Above this limit a quota is certainly mislabelled: for a 200 GB daily
     * quota the account page shows "197040 GB" but means MB. Only intervene
     * from 16 TiB so that purchased extra traffic (e.g. 1 TB) stays untouched.
     */
    private val MAX_PLAUSIBLE_QUOTA = 16L shl 40

    /** Lenient parsing of the API expiry date: several formats or Unix time; 0 = unknown. */
    fun parseExpire(raw: String?): Long {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("null", true)) return 0L
        value.toLongOrNull()?.let { return if (it > 10_000_000_000L) it else it * 1000 }
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "dd MMMM yyyy", "dd MMM yyyy", "dd.MM.yyyy", "MM/dd/yyyy"
        )
        for (fmt in formats) {
            for (locale in listOf(Locale.US, Locale.GERMAN)) {
                runCatching { SimpleDateFormat(fmt, locale).parse(value)?.time }.getOrNull()?.let { return it }
            }
        }
        return 0L
    }

    /** The API reports premium_traffic_left in megabytes (docs: 102400 = 100 GB). */
    fun quotaToBytes(raw: Double): Long = (raw * (1L shl 20)).toLong()

    /** Corrects a mislabelled unit: divide by 1024 until the value is plausible. */
    fun plausibleQuota(bytes: Long): Long {
        var v = bytes
        var guard = 0
        while (v > MAX_PLAUSIBLE_QUOTA && guard++ < 4) v /= 1024
        return v
    }

    /**
     * Expiry date from the account page: "Premium expire: 2 December 2026",
     * "Aktiv bis 2 December 2026", "Active until 02 Dec 2026"; 0 = unknown.
     */
    fun pageExpire(text: String): Long =
        Regex("""(?i)(?:expires?|aktiv bis|active until|g[üu]ltig bis|valid until)\s*:?\s*([0-9]{1,2}\s+[A-Za-zÄÖÜäöü]+\s+[0-9]{4})""")
            .findAll(text)
            .map { parseExpire(it.groupValues[1]) }
            .firstOrNull { it > 0 } ?: 0L

    /** Account status according to the account page. */
    data class Status(
        /** Expiry in milliseconds; 0 = unknown. */
        val expire: Long,
        val premium: Boolean,
        /** "Ultimate" or "Premium"; only meaningful together with [premium]. */
        val tier: String
    )

    /**
     * Premium detection from the visible page text: a valid expiry date, or
     * (without a readable date) the word Premium/Ultimate as account status.
     * "Ultimate" only counts as account status, since as an advertisement
     * ("Ultimate Key aktivieren") the word also appears on free accounts.
     */
    fun status(pageText: String, now: Long = System.currentTimeMillis()): Status {
        val expire = pageExpire(pageText)
        val ultimate = Regex("""(?i)Account[- ]?(?:type|status)\s*:?\s*Ultimate\b|Ultimate Premium account""")
            .containsMatchIn(pageText)
        val freeAccount = Regex("""(?i)Account[- ]?(?:type|status)\s*:?\s*Free\b""").containsMatchIn(pageText)
        val premiumWord = !freeAccount &&
            (ultimate || Regex("""(?i)\bPremium\b""").containsMatchIn(pageText))
        val premium = expire > now || (expire == 0L && premiumWord)
        return Status(expire, premium, if (ultimate) "Ultimate" else "Premium")
    }

    /** Result of the quota search on the account page; -1 = not found. */
    data class TrafficParse(
        val left: Long,
        val total: Long,
        val unlimited: Boolean
    )

    /** Visible page text: tags removed, whitespace collapsed. */
    fun visibleText(html: String): String =
        html.replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Reads the quota from the account page, tolerant of different layouts:
     * "Traffic available: 120.5 GB", "Premium traffic left 120,5 GB / 200 GB",
     * "120.5 GB of 200 GB traffic", "Verfügbarer Traffic 120 GB",
     * "Verfügbare Daten 197040 GB" (Ultimate), "unlimited". Values are raw;
     * [plausibleTraffic] corrects the unit.
     */
    fun parseTraffic(html: String): TrafficParse {
        val text = visibleText(html)
        val size = """(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)\b"""
        val unit = { v: String, u: String -> toBytes(v.replace(',', '.'), u) }

        var left = -1L
        var total = -1L
        var unlimited = false

        // Purchase offers and ads ("200 GB + Daten €15.99", "200 GB traffic per
        // day") must never count as remaining traffic
        val offerLike = Regex("""(?i)^\s*(?:\+|per\s+day|pro\s+tag|/\s*(?:day|tag)|€|\$|&euro;)""")
        fun isOffer(m: MatchResult) = offerLike.containsMatchIn(text.substring(m.range.last + 1).take(14))
        // 0) unambiguous label right before the number
        val labelled = Regex(
            """(?i)(?:verf[üu]gbare?r?\s+(?:daten|traffic)|available\s+(?:data|traffic)|traffic\s+(?:available|left)|premium\s+traffic\s+left)\s*:?\s*$size"""
        ).findAll(text).firstOrNull { !isOffer(it) }
        // 1) word before number: "Traffic available: 120.5 GB", "Premium traffic left 120 GB"
        val after = Regex("""(?i)(?:traffic|verf[üu]gbare?r?\s+daten|available\s+data)[^0-9]{0,60}?$size""")
            .findAll(text).firstOrNull { !isOffer(it) }
        // 2) number before word: "120.5 GB traffic left"
        val before = Regex("""(?i)$size[^0-9]{0,40}?traffic""").findAll(text).firstOrNull { !isOffer(it) }
        val hit = labelled ?: listOfNotNull(after, before).minByOrNull { it.range.first }
        if (hit != null) {
            val (v, u) = hit.destructured
            left = unit(v, u)
            // Total right after it: "/ 200 GB", "of 200 GB", "von 200 GB"
            Regex("""(?i)^\s*(?:/|of|von)\s*$size""").find(text.substring(hit.range.last + 1))
                ?.let { t -> total = unit(t.groupValues[1], t.groupValues[2]) }
        } else if (Regex("""(?i)traffic[^.]{0,60}?(unlimited|unbegrenzt)|(unlimited|unbegrenzt)[^.]{0,40}?traffic""")
                .containsMatchIn(text)
        ) {
            unlimited = true
        }
        return TrafficParse(left, total, unlimited)
    }

    /** Quota with corrected unit (see [plausibleQuota]); -1 stays -1. */
    fun plausibleTraffic(parsed: TrafficParse): TrafficParse = parsed.copy(
        left = if (parsed.left >= 0) plausibleQuota(parsed.left) else parsed.left,
        total = if (parsed.total > 0) plausibleQuota(parsed.total) else parsed.total
    )

    /** API key from the account page (XFS shows it under "API"), only in an unambiguous form. */
    fun apiKeyFromPage(html: String): String? =
        Regex("""(?i)api[\s_-]*key[\s\S]{0,300}?value=["']([a-z0-9]{16,64})["']""").find(html)?.groupValues?.get(1)

    /** "1.2" + "GB" → bytes (1024-based); -1 for an unreadable number. */
    fun toBytes(value: String, unit: String): Long {
        val n = value.toDoubleOrNull() ?: return -1
        val factor = when (unit.uppercase()) {
            "TB" -> 1L shl 40
            "GB" -> 1L shl 30
            "MB" -> 1L shl 20
            "KB" -> 1L shl 10
            else -> 1L
        }
        return (n * factor).toLong()
    }
}
