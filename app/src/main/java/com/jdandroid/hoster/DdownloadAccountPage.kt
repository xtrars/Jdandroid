package com.jdandroid.hoster

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reine Auswertung der ddownload-Kontoseite und der Kontowerte der API
 * (ohne Netz, ohne Android): Ablaufdatum, Premium-Erkennung, Kontingent
 * samt Plausibilitaetsgrenze und API-Key. Jede Regel ist damit gegen echte
 * Seitenausschnitte pruefbar, der Hoster selbst bleibt beim Ablauf.
 */
internal object DdownloadAccountPage {

    /** Tageskontingent von ddownload Premium laut Anbieter (200 GB). */
    val DAILY_QUOTA = 200L shl 30

    /**
     * Oberhalb dieser Grenze ist ein Kontingent sicher falsch beschriftet: die
     * Kontoseite zeigt bei einem 200-GB-Tageskontingent "197040 GB", meint
     * aber MB. Erst ab 16 TiB eingreifen, damit dazugekaufter Traffic
     * (z.B. 1 TB) unangetastet bleibt.
     */
    private val MAX_PLAUSIBLE_QUOTA = 16L shl 40

    /** Ablaufdatum der API tolerant lesen: mehrere Formate oder Unix-Zeit; 0 = unbekannt. */
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

    /** premium_traffic_left liefert die API in Megabyte (Doku: 102400 = 100 GB). */
    fun quotaToBytes(raw: Double): Long = (raw * (1L shl 20)).toLong()

    /** Falsch beschriftete Einheit korrigieren: durch 1024 teilen, bis der Wert plausibel ist. */
    fun plausibleQuota(bytes: Long): Long {
        var v = bytes
        var guard = 0
        while (v > MAX_PLAUSIBLE_QUOTA && guard++ < 4) v /= 1024
        return v
    }

    /**
     * Ablaufdatum von der Kontoseite: "Premium expire: 2 December 2026",
     * "Aktiv bis 2 December 2026", "Active until 02 Dec 2026"; 0 = unbekannt.
     */
    fun pageExpire(text: String): Long =
        Regex("""(?i)(?:expires?|aktiv bis|active until|g[üu]ltig bis|valid until)\s*:?\s*([0-9]{1,2}\s+[A-Za-zÄÖÜäöü]+\s+[0-9]{4})""")
            .findAll(text)
            .map { parseExpire(it.groupValues[1]) }
            .firstOrNull { it > 0 } ?: 0L

    /** Kontostatus laut Kontoseite. */
    data class Status(
        /** Ablaufdatum in Millisekunden; 0 = unbekannt. */
        val expire: Long,
        val premium: Boolean,
        /** "Ultimate" oder "Premium" - nur mit [premium] aussagekraeftig. */
        val tier: String
    )

    /**
     * Premium-Erkennung aus dem sichtbaren Text der Kontoseite: gueltiges
     * Ablaufdatum, sonst (ohne lesbares Datum) das Wort Premium/Ultimate als
     * Kontostatus. "Ultimate" zaehlt nur als Kontostatus - als Werbung
     * ("Ultimate Key aktivieren") steht das Wort auch auf Free-Konten.
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

    /** Ergebnis der Kontingent-Suche auf der Kontoseite; -1 = nicht gefunden. */
    data class TrafficParse(
        val left: Long,
        val total: Long,
        val unlimited: Boolean
    )

    /** Sichtbaren Text der Seite gewinnen: Tags raus, Whitespace buendeln. */
    fun visibleText(html: String): String =
        html.replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Kontingent aus der Kontoseite lesen, tolerant gegen verschiedene Layouts:
     * "Traffic available: 120.5 GB", "Premium traffic left 120,5 GB / 200 GB",
     * "120.5 GB of 200 GB traffic", "Verfügbarer Traffic 120 GB",
     * "Verfügbare Daten 197040 GB" (Ultimate), "unlimited". Die Werte sind
     * roh; die Einheitenkorrektur macht [plausibleTraffic].
     */
    fun parseTraffic(html: String): TrafficParse {
        val text = visibleText(html)
        val size = """(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)\b"""
        val unit = { v: String, u: String -> toBytes(v.replace(',', '.'), u) }

        var left = -1L
        var total = -1L
        var unlimited = false

        // Kaufangebote und Werbung ("200 GB + Daten €15.99", "200 GB traffic per
        // day") duerfen nie als Restmenge gelten
        val offerLike = Regex("""(?i)^\s*(?:\+|per\s+day|pro\s+tag|/\s*(?:day|tag)|€|\$|&euro;)""")
        fun isOffer(m: MatchResult) = offerLike.containsMatchIn(text.substring(m.range.last + 1).take(14))
        // 0) Eindeutige Beschriftung direkt vor der Zahl
        val labelled = Regex(
            """(?i)(?:verf[üu]gbare?r?\s+(?:daten|traffic)|available\s+(?:data|traffic)|traffic\s+(?:available|left)|premium\s+traffic\s+left)\s*:?\s*$size"""
        ).findAll(text).firstOrNull { !isOffer(it) }
        // 1) Wort vor Zahl: "Traffic available: 120.5 GB", "Premium traffic left 120 GB"
        val after = Regex("""(?i)(?:traffic|verf[üu]gbare?r?\s+daten|available\s+data)[^0-9]{0,60}?$size""")
            .findAll(text).firstOrNull { !isOffer(it) }
        // 2) Zahl vor Wort: "120.5 GB traffic left"
        val before = Regex("""(?i)$size[^0-9]{0,40}?traffic""").findAll(text).firstOrNull { !isOffer(it) }
        val hit = labelled ?: listOfNotNull(after, before).minByOrNull { it.range.first }
        if (hit != null) {
            val (v, u) = hit.destructured
            left = unit(v, u)
            // Gesamt direkt dahinter: "/ 200 GB", "of 200 GB", "von 200 GB"
            Regex("""(?i)^\s*(?:/|of|von)\s*$size""").find(text.substring(hit.range.last + 1))
                ?.let { t -> total = unit(t.groupValues[1], t.groupValues[2]) }
        } else if (Regex("""(?i)traffic[^.]{0,60}?(unlimited|unbegrenzt)|(unlimited|unbegrenzt)[^.]{0,40}?traffic""")
                .containsMatchIn(text)
        ) {
            unlimited = true
        }
        return TrafficParse(left, total, unlimited)
    }

    /** Kontingent mit korrigierter Einheit (siehe [plausibleQuota]); -1 bleibt -1. */
    fun plausibleTraffic(parsed: TrafficParse): TrafficParse = parsed.copy(
        left = if (parsed.left >= 0) plausibleQuota(parsed.left) else parsed.left,
        total = if (parsed.total > 0) plausibleQuota(parsed.total) else parsed.total
    )

    /** API-Key von der Kontoseite (XFS zeigt ihn unter "API"), nur mit klarer Form. */
    fun apiKeyFromPage(html: String): String? =
        Regex("""(?i)api[\s_-]*key[\s\S]{0,300}?value=["']([a-z0-9]{16,64})["']""").find(html)?.groupValues?.get(1)

    /** "1.2" + "GB" → Bytes (1024-basiert); -1 bei unlesbarer Zahl. */
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
