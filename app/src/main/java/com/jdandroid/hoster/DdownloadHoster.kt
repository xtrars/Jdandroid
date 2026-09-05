package com.jdandroid.hoster

import com.jdandroid.core.FileNames
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
import java.util.concurrent.TimeUnit

/**
 * ddownload.com – XFileSharing-Hoster. Login über Benutzername/Passwort
 * (Session-Cookie), Premium-Direktdownload über die zweistufige
 * Download-Form. Free-Downloads laufen über [DdownloadFree]: Sperren und
 * Wartezeiten liest die App selbst von der Seite, das Cloudflare-Turnstile
 * im Download-Formular ist nur im eingebetteten Browser lösbar. Die
 * Kontoseite wertet [DdownloadAccountPage] aus.
 */
class DdownloadHoster internal constructor(
    /** Adresse der Website; Tests ersetzen sie durch einen lokalen Server. */
    internal val siteBase: String
) : Hoster {

    constructor() : this("https://ddownload.com")

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

    /** Free-Downloads (Wartezeit + Turnstile im Browser) sind umgesetzt. */
    override val supportsFree = true

    private val apiBase = "https://api-v2.ddownload.com/api"

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
    private val fileServerRegex =
        Regex("""https?://(?!www\.)[a-z0-9-]+\.(?:ddownload\.com|ddl\.to)(?::\d+)?/[^\s"'<>]+""")

    /** Hauptdomains, auf denen nie eine Datei liegt (nur Seiten). */
    override val siteHosts = setOf("ddownload.com", "www.ddownload.com", "ddl.to", "www.ddl.to")

    /** Browsertypischer User-Agent: XFileSharing/Cloudflare mögen keine Bot-Kennungen. */
    internal val browserUa: String
        get() = Http.browserUserAgent
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"

    /** Ein Cookie-Speicher (Session) pro Account-Id. */
    private val cookieStores = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Cookie>>()
    private val clients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    override fun matches(url: String) = pattern.containsMatchIn(url)

    internal fun fileCode(url: String): String =
        pattern.find(url)?.groupValues?.get(1)
            ?: throw HosterException("Ungültiger ddownload-Link", true)

    internal data class Resp(
        val code: Int,
        val body: String,
        val location: String? = null,
        val contentType: String? = null,
        /** Adresse nach allen gefolgten Weiterleitungen. */
        val finalUrl: String = "",
        val contentDisposition: String? = null
    ) {
        /** Antwort ist eine Datei, keine Seite. */
        val isFile: Boolean
            get() = contentDisposition?.contains("attachment", true) == true ||
                (!contentType.isNullOrBlank() && !isTextualType(contentType))
    }

    internal fun clientFor(accountId: Long): OkHttpClient = clients.getOrPut(accountId) {
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
                    synchronized(store) {
                        val now = System.currentTimeMillis()
                        store.filter { it.matches(url) && it.expiresAt > now }
                    }
            })
            .build()
    }

    internal fun OkHttpClient.fetch(
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
                runCatching { resp.peekBody(Http.MAX_TEXT_BYTES).string() }.getOrDefault("")
            } else {
                ""
            }
            Resp(
                resp.code, text, resp.header("Location"), contentType,
                resp.request.url.toString(), resp.header("Content-Disposition")
            )
        }
    }

    /** Nur textartige Antworten duerfen in den Speicher gelesen werden. */
    internal fun isTextual(contentType: String?): Boolean =
        contentType.isNullOrBlank() || isTextualType(contentType)

    /** Erkennt Cloudflare-/WAF-Blockaden, damit die Meldung nicht "falsches Passwort" lautet. */
    internal fun checkBlocked(resp: Resp) {
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

        var page = client.fetch("$siteBase/?op=my_account", referer = siteBase)
        checkBlocked(page)
        if (page.code !in 200..299) {
            // Serverfehler oder Wartungsseite: voruebergehend, kein Grund, das
            // Konto abzuschalten
            throw HosterException("ddownload: Kontoseite nicht erreichbar (HTTP ${page.code})", permanent = false)
        }
        if (!isLoggedIn(page.body)) {
            // Der Cookie-Speicher kann eine vom Server "geloeschte" Session
            // tragen (Set-Cookie mit Ablauf in der Vergangenheit). Einmal mit
            // den gespeicherten Browser-Cookies neu beginnen, bevor die
            // Session als abgelaufen gilt.
            seedCookies(account.id, raw, force = true)
            page = client.fetch("$siteBase/?op=my_account", referer = siteBase)
            checkBlocked(page)
            if (page.code !in 200..299) {
                throw HosterException("ddownload: Kontoseite nicht erreichbar (HTTP ${page.code})", permanent = false)
            }
        }
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
    private fun seedCookies(accountId: Long, raw: String, force: Boolean = false) {
        val store = cookieStores.getOrPut(accountId) { mutableListOf() }
        synchronized(store) {
            if (store.isNotEmpty() && !force) return
            store.clear()
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
            // Oeffnendes Anfuehrungszeichen merken: ein Apostroph im Wert
            // ("It's.a.file.mkv") darf den Wert nicht abschneiden
            val name = Regex("""\bname=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: return@forEach
            val value = Regex("""\bvalue=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(2) ?: ""
            result[name] = value
        }
        return result
    }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val key = account.plainApiKey?.takeIf { it.isNotBlank() }
        if (key != null) return@withContext checkViaApi(key)

        val (_, html) = sessionAndAccountPage(account)
        val (expire, premium, tier) = DdownloadAccountPage.status(DdownloadAccountPage.visibleText(html))

        // Steht auf der Kontoseite ein API-Key, liefert die API das Kontingent
        // zuverlaessiger als die HTML-Seite (premium_traffic_left). Premium gilt,
        // wenn Seite ODER API es sagen - die Seite ist bei unklarem Datumsformat
        // der API die verlaesslichere Quelle.
        DdownloadAccountPage.apiKeyFromPage(html)?.let { key ->
            runCatching { checkViaApi(key) }.getOrNull()?.let { viaApi ->
                if (viaApi.trafficLeft >= 0 || viaApi.trafficUnlimited) {
                    val apiPremium = viaApi.statusText.startsWith("Premium")
                    return@withContext if (premium) {
                        viaApi.copy(
                            premiumUntil = if (viaApi.premiumUntil > 0) viaApi.premiumUntil else expire,
                            statusText = if (apiPremium) viaApi.statusText.replaceFirst("Premium", tier) else tier
                        )
                    } else viaApi
                }
            }
        }

        val traffic = DdownloadAccountPage.plausibleTraffic(DdownloadAccountPage.parseTraffic(html))
        val trafficTotal = when {
            traffic.unlimited -> -1L
            traffic.total > 0 -> traffic.total
            premium && traffic.left >= 0 -> maxOf(DdownloadAccountPage.DAILY_QUOTA, traffic.left)
            else -> -1L
        }

        AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = traffic.left,
            trafficTotal = trafficTotal,
            trafficUnlimited = traffic.unlimited,
            statusText = buildString {
                append(if (premium) tier else freeStatusText)
                if (traffic.left < 0 && !traffic.unlimited) append(" · Kontingent nicht lesbar")
            }
        )
    }

    /** Kontopruefung ueber die offizielle API (ohne CAPTCHA). */
    private fun checkViaApi(key: String): AccountInfo {
        val json = apiCall("account/info", mapOf("key" to key))
        val result = json.optJSONObject("result")
            ?: throw HosterException("ddownload: unerwartete API-Antwort", true)
        val expire = DdownloadAccountPage.parseExpire(result.opt("premium_expire")?.toString())
        // Laut API-Doku: "premium_traffic_left" (in MB, z.B. 102400 = 100 GB) ist
        // das verbleibende Premium-Tageskontingent; "traffic_left"/"traffic_used"
        // betreffen den Free-Traffic und sind fuer Premium irrelevant.
        val rawPremiumLeft = result.opt("premium_traffic_left")?.toString()?.trim().orEmpty()
        val unlimited = rawPremiumLeft.contains("unlimited", true) || rawPremiumLeft == "inf"
        val premiumLeft = rawPremiumLeft.toDoubleOrNull()
        // Premium: gueltiges Ablaufdatum - oder, wenn die API das Datum in einem
        // unbekannten Format liefert, ein vorhandenes Premium-Kontingent
        // (ein Free-Konto hat keins). Vorher hiess ein unlesbares Datum "Free".
        val premium = expire > System.currentTimeMillis() ||
            (expire == 0L && (unlimited || (premiumLeft ?: 0.0) > 0))
        val left = when {
            unlimited -> -1L
            premiumLeft != null && premiumLeft >= 0 ->
                DdownloadAccountPage.plausibleQuota(DdownloadAccountPage.quotaToBytes(premiumLeft))
            else -> {
                // Aeltere API ohne premium_traffic_left: traffic_left als Notnagel
                result.opt("traffic_left")?.toString()?.trim()?.toDoubleOrNull()
                    ?.let { DdownloadAccountPage.quotaToBytes(it) } ?: -1L
            }
        }
        val total = when {
            unlimited -> -1L
            premium && left >= 0 -> maxOf(DdownloadAccountPage.DAILY_QUOTA, left)
            else -> -1L
        }
        return AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = left,
            trafficTotal = total,
            trafficUnlimited = unlimited,
            statusText = if (premium) "Premium" else freeStatusText
        )
    }

    private fun apiCall(path: String, params: Map<String, String>): org.json.JSONObject {
        val query = params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
        val (code, text) = Http.client.newCall(
            okhttp3.Request.Builder().url("$apiBase/$path?$query")
                .header("User-Agent", browserUa).build()
        ).execute().use { it.code to it.peekBody(Http.MAX_TEXT_BYTES).string() }
        // Cloudflare-/Fehlerseiten sind kein JSON: voruebergehend, nicht "Konto ungueltig"
        val json = runCatching { org.json.JSONObject(text) }.getOrElse {
            throw HosterException("ddownload-API: keine JSON-Antwort (HTTP $code)", permanent = false)
        }
        val status = json.optInt("status")
        if (status != 200) {
            val msg = json.optString("msg").ifBlank { "HTTP $status" }
            // Dauerhaft nur, was der Text als solches ausweist (Datei weg,
            // Schluessel ungueltig); Tageslimit, Sperren und Serverfehler sind
            // voruebergehend
            val permanent = status == 404 ||
                msg.contains("not found", true) || msg.contains("invalid key", true) ||
                msg.contains("wrong key", true) || msg.contains("no such", true)
            throw HosterException("ddownload: $msg", permanent = permanent)
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
            val (client, accountHtml) = sessionAndAccountPage(account)
            // Steht auf der Kontoseite ein API-Key, ist die API der sicherste Weg
            // zum Direktlink (kein Formular, keine Weiterleitungskette)
            DdownloadAccountPage.apiKeyFromPage(accountHtml)?.let { key ->
                runCatching { resolveViaApi(key, code) }
                    .onFailure { if (it is HosterException && it.permanent && it.message?.contains("offline") == true) throw it }
                    .getOrNull()?.let { return@withContext it }
            }
            val pageUrl = "$siteBase/$code"

            var page = client.fetch(pageUrl, referer = siteBase)
            checkBlocked(page)
            // Konto mit "Direct Downloads": die Seite leitet sofort zur Datei
            // weiter; der Client ist ihr gefolgt, die Endadresse ist der Link
            if (page.isFile && page.finalUrl.toHttpUrlOrNull()?.host?.lowercase() !in siteHosts) {
                return@withContext ResolvedLink(page.finalUrl, FileNames.fromDisposition(page.contentDisposition))
            }
            checkOffline(page.body)
            // Name von der Dateiseite merken: nach der Weiterleitungskette ist
            // der Seitentext eine Umleitung ohne Inhalt
            val pageName = pageFileName(page.body)

            var direct = extractDirectLink(page.body)
            var formsSent = 0
            var hops = 0
            var currentUrl = pageUrl
            while (direct == null && hops++ < 6) {
                if (page.code in 300..399 && !page.location.isNullOrBlank()) {
                    // Weiterleitung: zeigt sie auf eine Datei, ist das der Direktlink;
                    // sonst der naechsten Seite folgen (ohne die Datei selbst zu laden).
                    // Relative Ziele gegen die zuletzt geholte Adresse aufloesen.
                    val target = resolveLocation(currentUrl, page.location!!)
                    if (isFileServerUrl(target)) { direct = target; break }
                    page = client.fetch(target, referer = currentUrl, followRedirects = false)
                    currentUrl = target
                    checkBlocked(page)
                    // Antwort ist bereits die Datei (Adresse ohne Dateiendung,
                    // z.B. dl.cgi/<token>): der Koerper wurde nicht gelesen
                    if (page.code in 200..299 && page.isFile) { direct = target; break }
                    direct = extractDirectLink(page.body)
                    continue
                }
                if (formsSent >= 2) break
                // Download-Formular (op=download2, method_premium) abschicken. Ohne
                // Redirect-Folgen: XFileSharing antwortet mit einer Weiterleitung,
                // deren Location bereits der Direktlink ist.
                val form = downloadForm(page.body, code)
                formsSent++
                page = client.fetch(pageUrl, form = form, referer = pageUrl, followRedirects = false)
                currentUrl = pageUrl
                checkBlocked(page)
                if (page.code in 200..299 && page.isFile) { direct = page.finalUrl; break }
                direct = extractDirectLink(page.body)
                if (direct == null && page.code !in 300..399) checkOffline(page.body)
            }
            if (direct.isNullOrBlank()) {
                val text = DdownloadAccountPage.visibleText(page.body)
                val limitReached = Regex("""(?i)download limit|reached the|limit reached|too many|try again later""")
                    .containsMatchIn(text)
                val freeMode = page.body.contains("countdown", true) ||
                    Regex("""(?i)name=["']method_free["'][^>]*value=["'][^"']+""").containsMatchIn(page.body)
                val hint = when {
                    limitReached -> "Tageslimit erreicht oder Sperre – wird später erneut versucht"
                    text.contains("premium", true) && text.contains("only", true) ->
                        "Datei ist nur für Premium verfügbar"
                    freeMode -> "Server verlangt Wartezeit (Free-Modus)"
                    page.code in 300..399 -> "Weiterleitung ohne Datei"
                    else -> "unerwartete Antwort"
                }
                throw HosterException(
                    "ddownload: kein Direktlink erhalten (HTTP ${page.code}, $hint).",
                    permanent = resolveFailurePermanent(page.code, limitReached)
                )
            }
            val fileName = pageName ?: pageFileName(page.body)
                ?: direct.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }
            ResolvedLink(direct, fileName)
        }

    /** Free-Ablauf (Sperren, Countdown, Turnstile) - siehe [DdownloadFree]. */
    internal val free = DdownloadFree(this)

    override suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink = free.resolve(url, hints)

    /** Cookie-Header aus dem OkHttp-Speicher fuer [url]; null ohne passende Cookies. */
    internal fun cookieHeader(accountId: Long, url: String): String? {
        val http = url.toHttpUrlOrNull() ?: return null
        val store = cookieStores[accountId] ?: return null
        val now = System.currentTimeMillis()
        return synchronized(store) {
            store.filter { it.matches(http) && it.expiresAt > now }
                .joinToString("; ") { "${it.name}=${it.value}" }
        }.ifBlank { null }
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
            val resp = clientFor(0L).fetch("$siteBase/$code")
            val html = resp.body
            if (resp.code == 404 || html.contains("File Not Found", true) || html.contains("No such file", true)) {
                return@withContext LinkInfo(online = false, note = "Datei nicht gefunden")
            }
            if (html.contains("Just a moment", true) || html.contains("cf-challenge", true) ||
                html.contains("challenge-platform", true) || html.contains("Attention Required", true)
            ) {
                return@withContext LinkInfo(online = null, note = "Cloudflare-Prüfung – Status unbekannt")
            }
            // Fehlerseite (5xx, 403) ist kein Beleg fuer "online"
            if (resp.code !in 200..299) {
                return@withContext LinkInfo(online = null, note = "HTTP ${resp.code} – Status unbekannt")
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
        ).find(html)?.groupValues?.get(1)?.trim()?.removePrefix("Download ")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return com.jdandroid.core.ArchiveNames.repairName(it) }
        Regex("""name=["']fname["']\s+value=(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        Regex("""value=(["'])(.*?)\1\s+name=["']fname["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return Regex("""<title>([^<]+?)(?:\s*[-|–].*)?</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.removePrefix("Download ")?.trim()
            ?.let { com.jdandroid.core.ArchiveNames.repairName(it) }
            ?.takeIf {
                it.isNotBlank() && !it.contains("ddownload", true) &&
                    Regex("""\.[A-Za-z0-9]{1,10}$""").containsMatchIn(it)
            }
    }

    /** Groesse wie "1.2 GB" aus der Dateiseite. */
    internal fun pageFileSize(html: String): Long {
        // Nur sichtbarer Text, und erst ab dem Dateinamen: davor stehen
        // Werbung und Kontingent-Angaben ("200 GB traffic per day")
        val text = DdownloadAccountPage.visibleText(html)
        val start = pageFileName(html)?.let { text.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(KB|MB|GB|TB)\b""", RegexOption.IGNORE_CASE).find(text, start)
            ?: return -1
        return DdownloadAccountPage.toBytes(m.groupValues[1].replace(',', '.'), m.groupValues[2])
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

    /**
     * Gilt fuer Adressen mit Dateinamen auf einem anderen Host als der
     * Hauptdomain: Fileserver (Subdomain, auch mit Port wie :183, auch unter
     * /cgi-bin/dl.cgi/) und fremde CDN-Hosts, auf die die Formular-Antwort
     * weiterleiten kann. Seitenlinks, tracker.cgi, relative Weiterleitungen
     * und /login.html fallen durch Host- und Endungspruefung heraus.
     */
    internal fun isFileServerUrl(url: String): Boolean {
        if (DirectLinks.isDirectDownloadUrl(url, siteHosts)) return true
        // Fileserver-Subdomain mit Download-Pfad, auch ohne Dateiendung
        // (z.B. /cgi-bin/dl.cgi/<token>): so antwortet das Free-Formular
        val http = url.toHttpUrlOrNull() ?: return false
        val host = http.host.lowercase()
        if (host in siteHosts || host in serviceHosts) return false
        if (!Regex("""^[a-z0-9-]+\.(?:ddownload\.com|ddl\.to)$""").matches(host)) return false
        return Regex("""^/(?:cgi-bin/dl\.cgi|d|files)/[^/]+""").containsMatchIn(http.encodedPath) &&
            http.pathSegments.lastOrNull().orEmpty().isNotEmpty()
    }

    /** Subdomains mit Seiten oder API, nie mit Dateien. */
    private val serviceHosts = setOf("my.ddownload.com", "api-v2.ddownload.com", "my.ddl.to")

    override fun isDirectDownloadUrl(url: String): Boolean = isFileServerUrl(url)

    /** Location-Header (auch relativ) gegen die Seitenadresse aufloesen. */
    internal fun resolveLocation(base: String, location: String): String =
        base.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location

    /**
     * Direktlink aus dem HTML. Das frühere Muster "URL enthält download" traf
     * jede Adresse der Website selbst ("ddownload.com" enthält "download") und
     * lieferte dadurch Seitenlinks statt der Datei; spaeter galt auch
     * tracker.cgi auf der Hauptdomain faelschlich als Direktlink.
     */
    internal fun extractDirectLink(html: String): String? =
        fileServerRegex.findAll(html)
            .map { it.value.trimEnd('\\', '"', '\'', ')').replace("&amp;", "&") }
            .firstOrNull { isFileServerUrl(it) }

    /**
     * Kein Direktlink: dauerhaft nur ohne Limit-Hinweis und ohne Serverfehler.
     * 5xx und 429 (Drosselung) sind voruebergehend; checkBlocked() faengt nur
     * 403/503 ab, alles andere landete sonst endgueltig in FAILED.
     */
    internal fun resolveFailurePermanent(code: Int, limitReached: Boolean): Boolean =
        !limitReached && code !in 500..599 && code != 429

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
}

/** Textartige Antworten (Seiten, JSON) - alles andere ist Dateiinhalt. */
private fun isTextualType(contentType: String): Boolean {
    val type = contentType.lowercase()
    return type.startsWith("text/") || type.contains("html") || type.contains("json") ||
        type.contains("xml") || type.contains("javascript")
}
