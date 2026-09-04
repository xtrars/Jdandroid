package com.jdandroid.hoster

import com.jdandroid.data.Account
import com.jdandroid.data.plainApiKey
import com.jdandroid.data.plainCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ddownload.com – XFileSharing-Hoster. Login über Benutzername/Passwort
 * (Session-Cookie), Premium-Direktdownload über die zweistufige
 * Download-Form. Free-Downloads (Wartezeit/Captcha) werden nicht unterstützt.
 */
class DdownloadHoster : Hoster {

    override val id = "ddownload"
    override val displayName = "ddownload"
    // Der Weblogin von ddownload ist durch Cloudflare Turnstile geschützt und
    // headless nicht lösbar. Deshalb zwei Wege: API-Key (empfohlen, läuft ohne
    // CAPTCHA) oder Anmeldung im eingebetteten Browser mit Session-Übernahme.
    override val accountType = AccountType.API_KEY
    override val accountHint =
        "Empfohlen: API-Key aus dem ddownload-Konto (my.ddownload.com → API). " +
            "Alternativ unten \"Im Browser anmelden\" für Benutzername/Passwort – " +
            "der Login verlangt ein CAPTCHA und geht nur im Browser."
    override val webLoginUrl = "https://ddownload.com/login.html"

    private val apiBase = "https://api-v2.ddownload.com/api"

    /** Obergrenze fuer als Text gelesene Antworten (HTML-Seiten sind klein). */
    private val MAX_TEXT_BYTES = 2L * 1024 * 1024

    private val siteBase = "https://ddownload.com"

    /** Tageskontingent von ddownload Premium laut Anbieter (200 GB). */
    private val DAILY_QUOTA = 200L shl 30

    /**
     * Einheit von premium_traffic_left: Die Doku nennt keine. In der Praxis
     * liefert ddownload Kilobyte (als MB gerechnet erschienen 193 TiB statt
     * 193 GiB). Absicherung: Ergibt KB einen Wert weit ueber dem Tages-
     * kontingent, ist der Rohwert bereits in Byte.
     */
    internal fun quotaToBytes(raw: Double): Long {
        val asKb = raw * 1024
        return if (asKb <= 4.0 * DAILY_QUOTA) asKb.toLong() else raw.toLong()
    }

    /**
     * Plausibilitaet: Ein Rest oberhalb des Vierfachen des Tageskontingents
     * kann nur eine falsch beschriftete Einheit sein (KB als MB gelesen ergab
     * 193,9 TiB statt 193,9 GiB). Dann so lange durch 1024 teilen, bis der
     * Wert ins Kontingent passt.
     */
    internal fun plausibleQuota(bytes: Long): Long {
        var v = bytes
        var guard = 0
        while (v > 4 * DAILY_QUOTA && guard++ < 4) v /= 1024
        return v
    }
    /**
     * Dateicodes sind genau 12 Zeichen [a-z0-9]; der Lookahead verhindert,
     * dass laengere Pfade (z.B. /register.html) als Code gelesen werden.
     */
    private val pattern =
        Regex("""https?://(?:www\.)?(?:ddownload\.com|ddl\.to)/(?:f/|d/)?([a-z0-9]{12})(?![A-Za-z0-9])""")

    /**
     * Direktlinks liegen auf Fileservern (Subdomain ausser www), nie auf der
     * Hauptdomain und nie unter /cgi-bin/ (dort liegt z.B. tracker.cgi).
     */
    private val fileServerRegex = Regex("""https?://(?!www\.)[a-z0-9-]+\.ddownload\.com/[^\s"'<>]+""")

    /** Browsertypischer User-Agent: XFileSharing/Cloudflare mögen keine Bot-Kennungen. */
    private val browserUa =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    /** Ein Cookie-Speicher (Session) pro Account-Id. */
    private val cookieStores = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Cookie>>()
    private val clients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    override fun matches(url: String) = pattern.containsMatchIn(url)

    private fun fileCode(url: String): String =
        pattern.find(url)?.groupValues?.get(1)
            ?: throw HosterException("Ungültiger ddownload-Link", true)

    private data class Resp(
        val code: Int,
        val body: String,
        val location: String? = null,
        val contentType: String? = null
    )

    private fun clientFor(accountId: Long): OkHttpClient = clients.getOrPut(accountId) {
        // OkHttp ruft den CookieJar aus mehreren Threads auf (parallele
        // Downloads desselben Kontos) - Zugriffe daher synchronisieren.
        val store = cookieStores.getOrPut(accountId) { mutableListOf() }
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    synchronized(store) {
                        for (c in cookies) {
                            store.removeAll { it.name == c.name && it.domain == c.domain }
                            store.add(c)
                        }
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    synchronized(store) { store.filter { it.matches(url) } }
            })
            .build()
    }

    private fun OkHttpClient.fetch(
        url: String,
        form: Map<String, String>? = null,
        referer: String? = null,
        followRedirects: Boolean = true
    ): Resp {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", browserUa)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "de,en;q=0.8")
        referer?.let { builder.header("Referer", it) }
        if (form != null) {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            builder.post(body)
        }
        val client = if (followRedirects) this else {
            newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
        return client.newCall(builder.build()).execute().use { resp ->
            val contentType = resp.header("Content-Type")
            // Antwortkoerper NIE unbegrenzt als Text lesen: folgt der Server der
            // Download-Weiterleitung, ist der Koerper die komplette Datei und
            // .string() sprengt den Heap (OutOfMemoryError).
            val text = if (isTextual(contentType)) {
                runCatching { resp.peekBody(MAX_TEXT_BYTES).string() }.getOrDefault("")
            } else {
                ""
            }
            Resp(resp.code, text, resp.header("Location"), contentType)
        }
    }

    /** Nur textartige Antworten duerfen in den Speicher gelesen werden. */
    internal fun isTextual(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return true
        val type = contentType.lowercase()
        return type.startsWith("text/") ||
            type.contains("html") ||
            type.contains("json") ||
            type.contains("xml") ||
            type.contains("javascript")
    }

    /** Erkennt Cloudflare-/WAF-Blockaden, damit die Meldung nicht "falsches Passwort" lautet. */
    private fun checkBlocked(resp: Resp) {
        val blocked = resp.code == 403 || resp.code == 503 ||
            resp.body.contains("Just a moment", true) ||
            resp.body.contains("cf-browser-verification", true) ||
            resp.body.contains("Attention Required", true) ||
            resp.body.contains("Enable JavaScript and cookies", true)
        if (blocked) {
            throw HosterException(
                "ddownload: Zugriff von Cloudflare blockiert (HTTP ${resp.code}). " +
                    "Bitte später erneut versuchen oder im Browser einmal die Seite öffnen.",
                permanent = false
            )
        }
    }

    /** Eingeloggt erkennt man zuverlässig am Logout-Link, nicht am Cookie-Namen. */
    private fun isLoggedIn(html: String): Boolean =
        html.contains("op=logout", true) ||
            (html.contains("?op=my_account", true) && html.contains("Account type", true))

    /**
     * Liefert Client plus HTML der Kontoseite auf Basis der im Browser
     * uebernommenen Session-Cookies. Ein headless Formular-Login ist wegen
     * des Turnstile-CAPTCHAs nicht moeglich.
     */
    private fun sessionAndAccountPage(account: Account): Pair<OkHttpClient, String> {
        val raw = account.plainCookies
        if (raw.isNullOrBlank()) {
            throw HosterException(
                "ddownload: keine Anmeldung hinterlegt. Entweder API-Key eintragen " +
                    "oder \"Im Browser anmelden\" verwenden (Login verlangt ein CAPTCHA).",
                permanent = true
            )
        }
        val client = clientFor(account.id)
        seedCookies(account.id, raw)

        val page = client.fetch("$siteBase/?op=my_account", referer = siteBase)
        checkBlocked(page)
        if (!isLoggedIn(page.body)) {
            throw HosterException(
                "ddownload: Browser-Session abgelaufen. Bitte unter Konten erneut " +
                    "\"Im Browser anmelden\".",
                permanent = true
            )
        }
        return client to page.body
    }

    /** Cookie-String aus dem Browser in den OkHttp-Cookie-Speicher uebernehmen. */
    private fun seedCookies(accountId: Long, raw: String) {
        val store = cookieStores.getOrPut(accountId) { mutableListOf() }
        synchronized(store) {
            if (store.isNotEmpty()) return
            raw.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isNotEmpty()) {
                    Cookie.Builder()
                        .name(name).value(value)
                        .domain("ddownload.com").path("/")
                        .build()
                        .let { store.add(it) }
                }
            }
        }
    }

    /** Alle hidden-Felder eines Formulars einsammeln (Reihenfolge der Attribute egal). */
    internal fun hiddenInputs(html: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val t = tag.value
            val name = Regex("""\bname=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(1) ?: return@forEach
            val value = Regex("""\bvalue=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(1) ?: ""
            result[name] = value
        }
        return result
    }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val key = account.plainApiKey?.takeIf { it.isNotBlank() }
        if (key != null) return@withContext checkViaApi(key)

        val (_, html) = sessionAndAccountPage(account)
        val expire = Regex(
            """[Pp]remium[^<:]*expire[^:<]*:?\s*(?:</?[^>]*>\s*)*([0-9]{1,2}\s+\w+\s+[0-9]{4})"""
        ).find(html)?.groupValues?.get(1)?.let { parseDate(it) } ?: 0L
        val premiumWord = Regex("""[Pp]remium""").containsMatchIn(html) &&
            !Regex("""Account type[^<]*(?:</?[^>]*>\s*)*Free""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)
        val premium = expire > System.currentTimeMillis() || (expire == 0L && premiumWord)

        // Steht auf der Kontoseite ein API-Key, liefert die API das Kontingent
        // zuverlaessiger als die HTML-Seite (premium_traffic_left).
        apiKeyFromPage(html)?.let { key ->
            runCatching { checkViaApi(key) }.getOrNull()?.let { viaApi ->
                if (viaApi.trafficLeft >= 0 || viaApi.trafficUnlimited) return@withContext viaApi
            }
        }

        val parsed = parseTraffic(html)
        val traffic = parsed.copy(
            left = if (parsed.left >= 0) plausibleQuota(parsed.left) else parsed.left,
            total = if (parsed.total > 0) plausibleQuota(parsed.total) else parsed.total
        )
        // Erkannten Wert immer festhalten: so laesst sich eine falsche Einheit
        // anhand des Seitenausschnitts nachvollziehen
        com.jdandroid.Diagnostics.sink?.invoke(
            "ddownload_account_parse",
            "ddownload-Kontoseite: erkanntes Kontingent",
            "rest=${parsed.left} (plausibel ${traffic.left}) gesamt=${parsed.total} unbegrenzt=${parsed.unlimited}\n" +
                parsed.snippet.take(1200)
        )
        if (traffic.left < 0 && !traffic.unlimited) {
            // Nicht lesbar: Seitenausschnitt fuer die Diagnose ablegen (nur Text,
            // keine Cookies/Zugangsdaten), damit das Muster nachgezogen werden kann.
            com.jdandroid.Diagnostics.sink?.invoke(
                "ddownload_account",
                "ddownload-Kontoseite: Kontingent nicht lesbar",
                traffic.snippet
            )
        }
        val trafficTotal = when {
            traffic.unlimited -> -1L
            traffic.total > 0 -> traffic.total
            premium && traffic.left >= 0 -> maxOf(DAILY_QUOTA, traffic.left)
            else -> -1L
        }

        AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = traffic.left,
            trafficTotal = trafficTotal,
            trafficUnlimited = traffic.unlimited,
            statusText = buildString {
                append(if (premium) "Premium" else "Free (Downloads nicht möglich)")
                if (traffic.left < 0 && !traffic.unlimited) append(" · Kontingent nicht lesbar (Diagnose in Einstellungen)")
            }
        )
    }

    /** Ergebnis der Kontingent-Suche auf der Kontoseite. */
    internal data class TrafficParse(
        val left: Long,
        val total: Long,
        val unlimited: Boolean,
        val snippet: String
    )

    /** Sichtbaren Text der Seite gewinnen: Tags raus, Whitespace buendeln. */
    internal fun visibleText(html: String): String =
        html.replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Kontingent aus der Kontoseite lesen, tolerant gegen verschiedene Layouts:
     * "Traffic available: 120.5 GB", "Premium traffic left 120,5 GB / 200 GB",
     * "120.5 GB of 200 GB traffic", "Verfügbarer Traffic 120 GB", "unlimited".
     */
    internal fun parseTraffic(html: String): TrafficParse {
        val text = visibleText(html)
        val size = """(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)\b"""
        val unit = { v: String, u: String -> toBytes(v.replace(',', '.'), u) }

        // Ausschnitte rund um jedes "traffic" fuer die Diagnose
        val snippet = Regex("""(?i)traffic""").findAll(text).take(8).joinToString(" | ") { m ->
            text.substring((m.range.first - 100).coerceAtLeast(0), (m.range.last + 160).coerceAtMost(text.length))
        }.ifBlank { "Kein Wort \"traffic\" auf der Seite gefunden. Anfang: " + text.take(600) }

        var left = -1L
        var total = -1L
        var unlimited = false

        // 1) Wort vor Zahl: "Traffic available: 120.5 GB", "Premium traffic left 120 GB"
        val after = Regex("""(?i)traffic[^0-9]{0,60}?$size""").find(text)
        // 2) Zahl vor Wort: "120.5 GB traffic left"
        val before = Regex("""(?i)$size[^0-9]{0,40}?traffic""").find(text)
        val hit = listOfNotNull(after, before).minByOrNull { it.range.first }
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
        return TrafficParse(left, total, unlimited, snippet)
    }

    /** API-Key von der Kontoseite (XFS zeigt ihn unter "API"), nur mit klarer Form. */
    internal fun apiKeyFromPage(html: String): String? =
        Regex("""(?i)api[\s_-]*key[\s\S]{0,300}?value=["']([a-z0-9]{16,64})["']""").find(html)?.groupValues?.get(1)

    /** Kontopruefung ueber die offizielle API (ohne CAPTCHA). */
    private fun checkViaApi(key: String): AccountInfo {
        val json = apiCall("account/info", mapOf("key" to key))
        val result = json.optJSONObject("result")
            ?: throw HosterException("ddownload: unerwartete API-Antwort", true)
        val expire = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .parse(result.optString("premium_expire"))?.time ?: 0L
        }.getOrDefault(0L)
        val premium = expire > System.currentTimeMillis()
        // Laut API-Doku: "premium_traffic_left" (in MB, z.B. 102400 = 100 GB) ist
        // das verbleibende Premium-Tageskontingent; "traffic_left"/"traffic_used"
        // betreffen den Free-Traffic und sind fuer Premium irrelevant.
        val rawPremiumLeft = result.opt("premium_traffic_left")?.toString()?.trim().orEmpty()
        val unlimited = rawPremiumLeft.contains("unlimited", true) || rawPremiumLeft == "inf"
        val premiumLeft = rawPremiumLeft.toDoubleOrNull()
        // Rohwert fuer die Diagnose festhalten (nur die Zahl, kein Schluessel)
        com.jdandroid.Diagnostics.sink?.invoke(
            "ddownload_api",
            "ddownload-API: Rohwerte der Kontoabfrage",
            "premium_traffic_left=$rawPremiumLeft traffic_left=${result.opt("traffic_left")} " +
                "traffic_used=${result.opt("traffic_used")} premium_expire=${result.opt("premium_expire")}"
        )
        val left = when {
            unlimited -> -1L
            premiumLeft != null -> plausibleQuota(quotaToBytes(premiumLeft))
            else -> {
                // Aeltere API ohne premium_traffic_left: traffic_left als Notnagel
                result.opt("traffic_left")?.toString()?.trim()?.toDoubleOrNull()
                    ?.let { quotaToBytes(it) } ?: -1L
            }
        }
        val total = when {
            unlimited -> -1L
            premium && left >= 0 -> maxOf(DAILY_QUOTA, left)
            else -> -1L
        }
        return AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = left,
            trafficTotal = total,
            trafficUnlimited = unlimited,
            statusText = if (premium) "Premium" else "Free (Downloads nicht möglich)"
        )
    }

    private fun apiCall(path: String, params: Map<String, String>): org.json.JSONObject {
        val query = params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        val resp = Http.client.newCall(
            okhttp3.Request.Builder().url("$apiBase/$path?$query")
                .header("User-Agent", browserUa).build()
        ).execute().use { it.body?.string() ?: "" }
        val json = org.json.JSONObject(resp)
        val status = json.optInt("status")
        if (status != 200) {
            val msg = json.optString("msg").ifBlank { "HTTP $status" }
            throw HosterException("ddownload: $msg", permanent = status in listOf(400, 403, 404))
        }
        return json
    }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            if (account == null) throw HosterException(
                "ddownload benötigt ein Premium-Konto (unter Konten hinzufügen).", true
            )
            val code = fileCode(url)
            account.plainApiKey?.takeIf { it.isNotBlank() }?.let { key ->
                return@withContext resolveViaApi(key, code)
            }
            val (client, _) = sessionAndAccountPage(account)
            val pageUrl = "$siteBase/$code"

            var page = client.fetch(pageUrl, referer = siteBase)
            checkBlocked(page)
            checkOffline(page.body)

            var direct = extractDirectLink(page.body)
            if (direct == null) {
                val form = downloadForm(page.body, code)
                // Ohne Redirect-Folgen: XFileSharing antwortet auf das Formular
                // mit einer Weiterleitung, deren Location bereits der
                // Direktlink ist. Wuerde man folgen, laedt man die Datei selbst.
                page = client.fetch(
                    pageUrl, form = form, referer = pageUrl, followRedirects = false
                )
                checkBlocked(page)
                // Location nur uebernehmen, wenn sie auf einen Fileserver zeigt -
                // relative Ziele oder /login.html sind kein Direktlink.
                direct = page.location?.takeIf { isFileServerUrl(it) }
                    ?: extractDirectLink(page.body)
                if (direct == null) checkOffline(page.body)
            }
            if (direct.isNullOrBlank()) {
                // Diagnosefaehige Meldung statt pauschalem "kein Premium"
                val hint = when {
                    page.body.contains("premium", true) &&
                        page.body.contains("only", true) -> "Datei ist nur für Premium verfügbar"
                    page.body.contains("countdown", true) ||
                        page.body.contains("wait", true) -> "Server verlangt Wartezeit (Free-Modus)"
                    else -> "unerwartete Antwort"
                }
                throw HosterException(
                    "ddownload: kein Direktlink erhalten (HTTP ${page.code}, $hint). " +
                        "Bei einem Free-Konto sind Downloads nicht möglich.",
                    permanent = true
                )
            }
            val fileName = pageFileName(page.body)
                ?: direct.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }
            ResolvedLink(direct, fileName)
        }

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            val code = fileCode(url)
            val key = account?.plainApiKey
            if (!key.isNullOrBlank()) {
                val info = apiCall("file/info", mapOf("key" to key, "file_code" to code))
                    .optJSONArray("result")?.optJSONObject(0)
                    ?: return@withContext LinkInfo(online = null, note = "Keine Antwort der API")
                val status = info.optInt("status")
                return@withContext if (status != 200) {
                    // 404 = nicht gefunden; jeder andere Status ist ebenfalls "nicht online"
                    val note = info.optString("msg").ifBlank { null }
                        ?: if (status == 404) "Datei nicht gefunden" else "Status $status"
                    LinkInfo(online = false, note = note)
                } else {
                    LinkInfo(
                        online = true,
                        fileName = info.optString("name").ifBlank { null },
                        fileSize = info.optLong("size", -1)
                    )
                }
            }
            // Ohne API-Key: oeffentliche Dateiseite auswerten (kein Login noetig).
            // Mit Browser-Kennung, sonst liefert Cloudflare nur eine Challenge.
            val html = clientFor(0L).fetch("$siteBase/$code").body
            if (html.contains("File Not Found", true) || html.contains("No such file", true)) {
                return@withContext LinkInfo(online = false, note = "Datei nicht gefunden")
            }
            if (html.contains("Just a moment", true) || html.contains("cf-challenge", true) ||
                html.contains("challenge-platform", true)
            ) {
                return@withContext LinkInfo(online = null, note = "Cloudflare-Prüfung – Status unbekannt")
            }
            LinkInfo(
                online = true,
                fileName = pageFileName(html),
                fileSize = pageFileSize(html)
            )
        }

    /**
     * Dateiname aus einer XFileSharing-Dateiseite. Reihenfolge: die
     * Ueberschrift mit Klasse dk-dl-name (aktuelles Layout), dann das
     * fname-Feld, zuletzt der Titel - dort ersetzt ddownload Punkte durch
     * Leerzeichen ("Download scn smps8 S37E02 rar"), er zaehlt daher nur,
     * wenn er noch eine Dateiendung traegt.
     */
    internal fun pageFileName(html: String): String? {
        Regex(
            """<h[12]\b[^>]*class=["'][^"']*\bdk-dl-name\b[^"']*["'][^>]*>\s*([^<]+?)\s*</h[12]>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("""name=["']fname["']\s+value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1].trim() }
        Regex("""value=["']([^"']+)["']\s+name=["']fname["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1].trim() }
        return Regex("""<title>([^<]+?)(?:\s*[-|–].*)?</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.removePrefix("Download ")?.trim()
            ?.takeIf {
                it.isNotBlank() && !it.contains("ddownload", true) &&
                    Regex("""\.[A-Za-z0-9]{1,10}$""").containsMatchIn(it)
            }
    }

    /** Groesse wie "1.2 GB" aus der Dateiseite. */
    internal fun pageFileSize(html: String): Long {
        val m = Regex("""([\d.,]+)\s*(KB|MB|GB|TB)\b""", RegexOption.IGNORE_CASE).find(html)
            ?: return -1
        return toBytes(m.groupValues[1].replace(',', '.'), m.groupValues[2])
    }

    /** Direktlink ueber die API (Premium erforderlich, kein CAPTCHA). */
    private fun resolveViaApi(key: String, code: String): ResolvedLink {
        var fileName: String? = null
        runCatching {
            val info = apiCall("file/info", mapOf("key" to key, "file_code" to code))
                .optJSONArray("result")?.optJSONObject(0)
            fileName = info?.optString("name")?.ifBlank { null }
            if (info?.optInt("status") == 404) throw HosterException("Datei ist offline", true)
        }.onFailure { if (it is HosterException && it.permanent) throw it }
        val result = apiCall("file/direct_link", mapOf("key" to key, "file_code" to code))
            .optJSONObject("result")
            ?: throw HosterException("ddownload lieferte keine Download-URL", true)
        val direct = result.optString("url")
        if (direct.isBlank()) {
            throw HosterException("ddownload lieferte keine Download-URL (Premium nötig?)", true)
        }
        return ResolvedLink(direct, fileName, result.optLong("size", -1))
    }

    private fun checkOffline(html: String) {
        if (html.contains("File Not Found", true) ||
            html.contains("file was deleted", true) ||
            html.contains("No such file", true)
        ) {
            throw HosterException("Datei ist offline", true)
        }
    }

    /**
     * Felder der Download-Form. Die Feldnamen von XFileSharing sind bekannt,
     * daher wird das Formular notfalls selbst aufgebaut: fuer angemeldete
     * Nutzer liefert die Seite teils kein vollstaendiges Formular, und daran
     * darf die Aufloesung nicht scheitern.
     */
    internal fun downloadForm(html: String, code: String): Map<String, String> {
        val block = formBlock(html) ?: html
        val inputs = hiddenInputs(block).toMutableMap()
        inputs["op"] = "download2"
        inputs["id"] = inputs["id"]?.ifBlank { code } ?: code
        inputs.putIfAbsent("rand", "")
        inputs.putIfAbsent("referer", "")
        inputs["method_free"] = ""
        inputs["method_premium"] = "1"
        return inputs
    }

    /** Das Formular mit der Download-Operation, damit keine Fremdfelder mitgehen. */
    internal fun formBlock(html: String): String? =
        Regex("""<form\b[^>]*>.*?</form>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .firstOrNull { it.value.contains("op\"", true) && it.value.contains("download", true) }
            ?.value

    private val assetExtensions = setOf(
        "html", "htm", "php", "css", "js", "png", "jpg", "jpeg", "gif",
        "svg", "ico", "woff", "woff2", "ttf", "webp", "json", "xml", "cgi"
    )

    /**
     * Gilt nur fuer Adressen auf einem Fileserver (Subdomain von ddownload.com
     * ausser www), nicht unter /cgi-bin/ und mit Dateiendung, die kein
     * Seiten-Asset ist. Damit fallen Seitenlinks, tracker.cgi, relative
     * Weiterleitungen und /login.html heraus.
     */
    internal fun isFileServerUrl(url: String): Boolean {
        if (fileServerRegex.matchEntire(url) == null) return false
        if (url.contains("/cgi-bin/", ignoreCase = true)) return false
        val path = url.substringAfter("://").substringAfter('/', "")
        val last = path.substringBefore('?').substringAfterLast('/')
        val ext = last.substringAfterLast('.', "").lowercase()
        return last.contains('.') && ext.isNotEmpty() && ext !in assetExtensions
    }

    /**
     * Direktlink aus dem HTML. Das frühere Muster "URL enthält download" traf
     * jede Adresse der Website selbst ("ddownload.com" enthält "download") und
     * lieferte dadurch Seitenlinks statt der Datei; spaeter galt auch
     * tracker.cgi auf der Hauptdomain faelschlich als Direktlink.
     */
    internal fun extractDirectLink(html: String): String? =
        fileServerRegex.findAll(html)
            .map { it.value.trimEnd('\\', '"', '\'', ')') }
            .firstOrNull { isFileServerUrl(it) }

    private fun parseDate(s: String): Long = runCatching {
        SimpleDateFormat("d MMMM yyyy", Locale.US).parse(s.trim())?.time ?: 0L
    }.getOrDefault(0L)

    private fun toBytes(value: String, unit: String): Long {
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

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
}
