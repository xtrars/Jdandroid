package com.jdandroid.hoster

import com.jdandroid.data.Account
import com.jdandroid.data.plainApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 1fichier.com – offizielle REST-API (API-Key, Premium/Access erforderlich);
 * ohne Konto der Free-Ablauf der Website ([resolveFree]: Dateiseite,
 * Countdown, Formular, Direktlink).
 */
class OneFichierHoster : Hoster {

    override val id = "onefichier"
    override val displayName = "1fichier"
    override val accountType = AccountType.API_KEY
    override val accountHint =
        "API-Key aus den 1fichier-Kontoeinstellungen (Bereich \"API\"), Premium/Access nötig."

    private val base = "https://api.1fichier.com/v1"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * Alle Domains, unter denen 1fichier Dateien ausliefert. Die Datei-Id ist
     * laut Doku kleingeschrieben; Grossbuchstaben werden beim Erkennen
     * toleriert und in [normalize] auf Kleinschreibung gebracht.
     */
    private val pattern = Regex(
        """https?://(?:www\.)?(?:1fichier\.com|alterupload\.com|cjoint\.net|desfichiers\.com|""" +
            """dfichiers\.com|megadl\.fr|mesfichiers\.org|piecejointe\.net|pjointe\.com|""" +
            """tenvoi\.com|dl4free\.com)/\?([A-Za-z0-9]{5,20})(?![A-Za-z0-9])"""
    )

    /** user/info.cgi erlaubt nur einen Aufruf pro 5 Minuten - Ergebnis zwischenspeichern. */
    private val accountCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, Result<AccountInfo>>>()
    private val accountCacheMs = 5L * 60 * 1000

    /** Fehler der Zugangsdaten (ungueltiger API-Key), immer permanent. */
    private class AuthException(message: String) : HosterException(message, permanent = true)

    override val siteHosts: Set<String> = listOf(
        "1fichier.com", "alterupload.com", "cjoint.net", "desfichiers.com", "dfichiers.com",
        "megadl.fr", "mesfichiers.org", "piecejointe.net", "pjointe.com", "tenvoi.com", "dl4free.com"
    ).flatMap { listOf(it, "www.$it") }.toSet()

    override val supportsFree = true

    override fun matches(url: String) = pattern.containsMatchIn(url)

    // ------------------------------------------------------------------
    // Free-Modus (Website-Ablauf ohne Konto)
    // ------------------------------------------------------------------

    private val siteBase = "https://1fichier.com"

    /**
     * Browser-Kennung wie in der Captcha-Ansicht: Dateiseite, Formular und
     * Dateiabruf laufen unter derselben Kennung und denselben Cookies.
     */
    private val browserUa: String
        get() = Http.browserUserAgent
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"

    /**
     * Zustand eines Free-Ablaufs je Datei-Kennung: eigener Cookie-Speicher
     * (Session der Dateiseite, `LG=en`), das ausgelesene Formular und der
     * Zeitpunkt, ab dem es abgeschickt werden darf. Lebt nur im Prozess.
     */
    private class FreeSession(val pageUrl: String) {
        var form: OneFichierForm? = null
        /** Hotlink: die Dateiseite lieferte schon die Datei, kein Formular noetig. */
        var hotlink: ResolvedLink? = null
        var fileName: String? = null
        var fileSize = -1L
        var readyAt = 0L
        val createdAt = System.currentTimeMillis()
        val store = mutableListOf<Cookie>()
        val client: OkHttpClient = Http.client.newBuilder()
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    synchronized(store) {
                        for (c in cookies) {
                            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                            store.add(c)
                        }
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
                    val now = System.currentTimeMillis()
                    store.filter { it.matches(url) && it.expiresAt > now }
                }
            })
            .build()

        val expired: Boolean get() = System.currentTimeMillis() - createdAt > SESSION_MAX_AGE_MS

        /** Cookie-Header fuer [url], null ohne passende Cookies. */
        fun cookieHeader(url: String): String? {
            val http = url.toHttpUrlOrNull() ?: return null
            val now = System.currentTimeMillis()
            return synchronized(store) {
                store.filter { it.matches(http) && it.expiresAt > now }
                    .joinToString("; ") { "${it.name}=${it.value}" }
            }.ifBlank { null }
        }
    }

    private val freeSessions = ConcurrentHashMap<String, FreeSession>()

    private data class Resp(
        val code: Int,
        val body: String,
        val location: String?,
        val finalUrl: String,
        /** Antwort ist eine Datei (Content-Disposition oder Nicht-Text). */
        val isFile: Boolean
    )

    private fun FreeSession.fetch(
        url: String,
        referer: String? = null,
        form: Map<String, String>? = null,
        followRedirects: Boolean = true
    ): Resp {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", browserUa)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-us,en;q=0.5")
        referer?.let { builder.header("Referer", it) }
        form?.let { f ->
            builder.post(FormBody.Builder().apply { f.forEach { (k, v) -> add(k, v) } }.build())
        }
        val c = if (followRedirects) client else {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }
        return c.newCall(builder.build()).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty().lowercase()
            val attachment = resp.header("Content-Disposition")?.contains("attachment", true) == true
            // Nur Seiten als Text lesen, nie eine Datei (Heap)
            val textual = !attachment && (
                type.isBlank() || type.startsWith("text/") || type.contains("json") ||
                    type.contains("javascript") || type.contains("xml")
                )
            val body = if (textual) runCatching { resp.peekBody(Http.MAX_TEXT_BYTES).string() }.getOrDefault("") else ""
            Resp(resp.code, body, resp.header("Location"), resp.request.url.toString(), resp.isSuccessful && !textual)
        }
    }

    private fun blockFor(block: OneFichierBlock): Nothing = when (block) {
        is OneFichierBlock.Wait -> throw WaitException(block.seconds, block.text)
        is OneFichierBlock.Permanent -> throw HosterException(block.text, permanent = true)
        is OneFichierBlock.Transient -> throw HosterException(block.text, permanent = false)
    }

    /**
     * Sperren und Hinweise einer Seite pruefen: HTTP-Status (503 Wartung,
     * 403/429 Sperre - nie permanent), Offline-Text, dann die Muster im
     * sichtbaren Text ([OneFichierFreePage.classify], auf der Dateiseite mit
     * [downloadOffered]). Kehrt zurueck, wenn nichts dagegen spricht.
     */
    private fun checkPage(resp: Resp, downloadOffered: Boolean = false) {
        if (resp.code == 404 || OneFichierFreePage.isOffline(resp.body)) throw HosterException("Datei ist offline", true)
        OneFichierFreePage.classify(OneFichierFreePage.visibleText(resp.body), downloadOffered)?.let { blockFor(it) }
        when {
            resp.code == 503 -> throw WaitException(20 * 60 + 1, "1fichier: Wartung – in 20 Minuten erneut")
            resp.code == 403 || resp.code == 429 -> throw WaitException(
                15 * 60 + 1, "1fichier: Zugriff vorübergehend gesperrt (HTTP ${resp.code}) – in 15 Minuten erneut"
            )
            resp.code !in 200..299 -> throw HosterException(
                "1fichier: Dateiseite nicht erreichbar (HTTP ${resp.code})", permanent = false
            )
        }
    }

    /**
     * Free-Modus ohne Konto. Ablauf ohne Nutzer: Dateiseite (englisch) holen,
     * Offline, Sperren mit Wartezeit und dauerhafte Gruende auswerten; ist die
     * Antwort bereits die Datei (Hotlink), wird sie direkt geladen. Sonst
     * das Download-Formular lesen, den Countdown (`var count`) abwarten -
     * kurz im Prozess, lang als [WaitException] an die Engine - und das
     * Formular abschicken; der Direktlink steht in der Antwort oder in der
     * Weiterleitung. Nur im Browser gehen: ein Captcha (nur bei auffaelligen
     * Adressen) und passwortgeschuetzte Dateien - beides als
     * [CaptchaRequiredException] mit der Dateiseite, der Nutzer arbeitet die
     * Seite dort durch und die Navigation auf `a-<n>.1fichier.com/<token>`
     * wird abgefangen ([FreeHints.direktUrlAusBrowser]).
     */
    override suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink =
        withContext(Dispatchers.IO) {
            val link = normalize(url) ?: throw HosterException("Ungültiger 1fichier-Link", true)
            val id = link.substringAfter("?")
            val pageUrl = "$link&lg=en"

            hints.direktUrlAusBrowser?.takeIf { it.isNotBlank() }?.let { direct ->
                val session = freeSessions.remove(id)
                return@withContext ResolvedLink(
                    secure(direct),
                    session?.fileName,
                    session?.fileSize ?: -1,
                    headers = freeHeaders(link, hints.cookies ?: session?.cookieHeader(direct))
                )
            }

            val session = freeSessions[id]?.takeUnless { it.expired || it.form == null }
                ?: startFreeSession(id, link, pageUrl)
            session.hotlink?.let { return@withContext it }

            // Countdown: kurze Reste im Prozess abwarten, lange an die Engine
            val remaining = session.readyAt - System.currentTimeMillis()
            if (remaining > MAX_INLINE_WAIT_MS) {
                throw WaitException(((remaining + 999) / 1000).toInt() + 1, WAIT_TEXT)
            }
            if (remaining > 0) delay(remaining + 500)

            val form = session.form ?: throw IllegalStateException("Formular fehlt")
            val action = form.action?.takeIf { it.startsWith("http", true) } ?: link
            var resp = session.fetch(action, referer = pageUrl, form = form.fields, followRedirects = false)
            var direct: String? = null
            var hops = 0
            var currentUrl = action
            while (direct == null && hops++ < 4) {
                if (resp.code in 300..399 && !resp.location.isNullOrBlank()) {
                    val target = resolveLocation(currentUrl, resp.location!!)
                    if (isDirectDownloadUrl(target)) { direct = target; break }
                    resp = session.fetch(target, referer = currentUrl, followRedirects = false)
                    currentUrl = target
                    continue
                }
                // Nach einer Weiterleitung per GET darf die Antwort die Datei sein;
                // die POST-Adresse selbst ist per GET nur die Dateiseite
                if (resp.isFile && currentUrl != action) { direct = resp.finalUrl; break }
                direct = OneFichierFreePage.directLink(resp.body)
                break
            }
            // Formular ist verbraucht: beim naechsten Versuch die Seite neu holen
            freeSessions.remove(id)
            if (direct.isNullOrBlank()) {
                checkPage(resp)
                if (OneFichierFreePage.hasCaptcha(resp.body)) {
                    throw CaptchaRequiredException(pageUrl, "1fichier: Bestätigung im Browser nötig")
                }
                throw HosterException("1fichier: kein Direktlink erhalten (HTTP ${resp.code})", permanent = false)
            }
            ResolvedLink(
                secure(direct),
                session.fileName,
                session.fileSize,
                headers = freeHeaders(link, session.cookieHeader(direct))
            )
        }

    /**
     * Dateiseite holen und auswerten. Liefert die Session mit Formular und
     * Startzeitpunkt; bei einem Hotlink (Antwort ist die Datei) steht der
     * fertige Link in [FreeSession.hotlink], ein Formular ist dann unnoetig.
     */
    private fun startFreeSession(id: String, link: String, pageUrl: String): FreeSession {
        freeSessions.remove(id)
        val session = FreeSession(pageUrl)
        // Englische Texte erzwingen, damit die Fehlermuster greifen
        session.store.add(Cookie.Builder().name("LG").value("en").domain("1fichier.com").path("/").build())
        val page = session.fetch(pageUrl, referer = "$siteBase/")
        if (page.isFile) {
            // Hotlink: Besitzer zahlt den Traffic, keine Wartezeit
            session.hotlink = ResolvedLink(
                secure(page.finalUrl), headers = freeHeaders(link, session.cookieHeader(page.finalUrl))
            )
            return session
        }
        // Mit Formular gilt der Hinweis "only one file at a time" nicht als
        // Sperre - ob gesperrt ist, zeigt erst die Antwort auf das Formular
        val form = OneFichierFreePage.downloadForm(page.body, id)
        checkPage(page, downloadOffered = form != null)
        if (OneFichierFreePage.hasCaptcha(page.body)) {
            throw CaptchaRequiredException(pageUrl, "1fichier: Bestätigung im Browser nötig")
        }
        if (form == null) throw HosterException("1fichier: Seite bietet keinen Free-Download an", permanent = false)
        if (form.needsPassword) {
            throw CaptchaRequiredException(pageUrl, "1fichier: Datei ist passwortgeschützt – Passwort im Browser eingeben")
        }
        session.form = form
        session.fileName = OneFichierFreePage.fileName(page.body)
        session.fileSize = OneFichierFreePage.fileSize(page.body)
        session.readyAt = System.currentTimeMillis() + OneFichierFreePage.countdownSeconds(page.body) * 1000L
        freeSessions[id] = session
        return session
    }

    private fun resolveLocation(base: String, location: String): String =
        base.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location

    /** Cleartext ist in der App gesperrt: Fileserver-Links immer ueber HTTPS. */
    private fun secure(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /**
     * Header fuer den Dateiabruf im Free-Modus: Browser-Kennung, Referer der
     * Dateiseite und die Session-Cookies, falls der Fileserver sie verlangt.
     */
    internal fun freeHeaders(pageUrl: String, cookies: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = browserUa
        headers["Referer"] = pageUrl
        cookies?.trim()?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        return headers
    }

    /**
     * Fileserver-Adresse `https://a-3.1fichier.com/<token>` (ohne Dateiendung,
     * daher neben [DirectLinks]); Seiten der Hauptdomain zaehlen nie.
     */
    override fun isDirectDownloadUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        if (host in siteHosts) return false
        return OneFichierFreePage.isFileServerUrl(url) || DirectLinks.isDirectDownloadUrl(url, siteHosts)
    }

    private companion object {
        /** Countdown-Reste bis hierhin laufen im Prozess ab, laengere gehen als Wartezeit an die Engine. */
        const val MAX_INLINE_WAIT_MS = 90_000L
        const val SESSION_MAX_AGE_MS = 10L * 60 * 1000
        const val WAIT_TEXT = "1fichier: Countdown vor dem Free-Download"
    }

    /**
     * Kanonische Form fuer API-Aufrufe: https://1fichier.com/?<id> (id klein),
     * unabhaengig von Alias-Domain, www. oder Anhaengseln. null = kein 1fichier-Link.
     */
    internal fun normalize(url: String): String? =
        pattern.find(url)?.groupValues?.get(1)?.lowercase()?.let { "https://1fichier.com/?$it" }

    private fun post(path: String, apiKey: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$base/$path")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", Http.USER_AGENT)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val (code, text) = Http.client.newCall(request).execute().use { resp ->
            // begrenzt lesen, siehe Http.MAX_TEXT_BYTES
            resp.code to resp.peekBody(Http.MAX_TEXT_BYTES).string()
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        val msg = json?.optString("message")?.ifBlank { null }
        // Voruebergehendes zuerst: Flood-Sperre (kommt auch als HTTP 403),
        // Rate-Limit, Serverfehler, Cloudflare-Seite. Nur ein klar
        // ausgewiesener Anmeldefehler darf das Konto dauerhaft abschalten -
        // ein pauschales 403 waere sonst das Aus fuer alle 1fichier-Downloads.
        val flood = msg?.let { it.contains("Flood", true) || it.contains("try again", true) } == true
        if (flood || code == 429 || code in 500..599) {
            throw HosterException("1fichier: ${msg ?: "Zu viele Anfragen (HTTP $code)"}", permanent = false)
        }
        if (code == 401 || (code == 403 && msg?.contains("Not authenticated", true) == true)) {
            throw AuthException("1fichier: ${msg ?: "Nicht angemeldet (HTTP $code)"}")
        }
        if (code == 403) {
            throw HosterException("1fichier: ${msg ?: "Zugriff blockiert (HTTP 403)"}", permanent = false)
        }
        if (json == null) throw HosterException("1fichier: unerwartete Antwort (HTTP $code)", permanent = false)
        if (json.optString("status") == "KO") throw koFailure(msg)
        return json
    }

    /**
     * Einordnung einer API-Antwort mit status=KO. Flood/Rate-Limit ist
     * voruebergehend; fehlende/geloeschte Datei und fehlendes Premium/Access
     * ("You must be a Premium/Access user") sind permanent - ein Free-Konto
     * kommt auch nach fuenf Versuchen nicht durch.
     */
    internal fun koFailure(message: String?): HosterException {
        val m = message?.ifBlank { null } ?: "Unbekannter Fehler"
        if (m.contains("Not authenticated", true)) return AuthException("1fichier: $m")
        val transient = m.contains("Flood", true) || m.contains("try again", true)
        val permanent = !transient && (
            m.contains("not found", true) ||
                m.contains("deleted", true) ||
                m.contains("no such", true) ||
                m.contains("not allowed", true) ||
                m.contains("Resource not", true) ||
                m.contains("premium", true) ||
                m.contains("must be", true)
        )
        return HosterException("1fichier: $m", permanent = permanent)
    }

    /**
     * Oeffentlicher Link-Check ohne API-Key: check_links.pl liefert je Zeile
     * "url;dateiname;groesse" fuer eine vorhandene Datei und "url;;NOT FOUND"
     * bzw. "url;BAD LINK" sonst. Entscheidend ist das letzte Feld, nicht die
     * Spaltenzahl - so bleibt die Auswertung gegen Formatvarianten robust.
     */
    internal fun parseCheckLine(line: String): LinkInfo {
        val parts = line.trim().split(';').map { it.trim() }
        if (parts.size < 2) return LinkInfo(online = null, note = "Unerwartete Antwort")
        val status = parts.last()
        val offline = listOf("NOT FOUND", "BAD LINK", "DELETED").any { status.contains(it, true) }
        if (offline) return LinkInfo(online = false, note = status.lowercase().replaceFirstChar { it.uppercase() })
        if (parts.size < 3) return LinkInfo(online = null, note = "Unerwartete Antwort")
        val name = parts[1].ifBlank { null }
        val size = parts[2].toLongOrNull() ?: -1
        return LinkInfo(online = true, fileName = name, fileSize = size)
    }

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            val link = normalize(url)
                ?: return@withContext LinkInfo(online = null, note = "Ungültiger 1fichier-Link")
            val form = okhttp3.FormBody.Builder().add("links[]", link).build()
            val request = Request.Builder()
                .url("https://1fichier.com/check_links.pl")
                .header("User-Agent", Http.USER_AGENT)
                .post(form)
                .build()
            val text = Http.client.newCall(request).execute().use { resp ->
                resp.peekBody(Http.MAX_TEXT_BYTES).string()
            }
            val line = text.lines().firstOrNull { it.contains("1fichier.com") }
                ?: return@withContext LinkInfo(online = null, note = "Keine Antwort vom Link-Check")
            parseCheckLine(line)
        }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val key = account.plainApiKey ?: throw HosterException("Kein API-Key hinterlegt", true)
        val now = System.currentTimeMillis()
        // Auch Fehlschlaege zwischenspeichern: 1fichier erlaubt user/info nur
        // alle 5 Minuten, und die Kontenansicht fragt jede Minute nach. Ein
        // wiederholter Fehlversuch wuerde sonst erst die Flood-Sperre ausloesen.
        accountCache[account.id]?.let { (at, cached) ->
            if (now - at < accountCacheMs) return@withContext cached.getOrThrow()
        }
        val result = runCatching { fetchAccount(key) }
        accountCache[account.id] = now to result
        result.getOrThrow()
    }

    private fun fetchAccount(key: String): AccountInfo {
        val json = post("user/info.cgi", key, JSONObject())
        // "offer" kann als Zahl (>0 = zahlend) oder als Text ("Premium"/"Access"/"Free") kommen
        val offerRaw = json.opt("offer")?.toString()?.trim().orEmpty()
        val endText = json.optString("subscription_end")
        val end = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(endText)?.time ?: 0L
        }.getOrDefault(0L)
        val premium = when {
            offerRaw.equals("Premium", true) || offerRaw.equals("Access", true) -> true
            offerRaw.toIntOrNull()?.let { it > 0 } == true -> true
            end > System.currentTimeMillis() -> true
            else -> false
        }
        // 1fichier begrenzt Premium/Access-Downloads nicht; CDN-Guthaben (GB) nur
        // als Hinweis. "cdn" ist nur das 0/1-Kennzeichen, der Betrag steht in
        // available_credits_in_gb.
        val cdnGb = json.optDouble("available_credits_in_gb", -1.0).takeIf { it >= 0 }
        return AccountInfo(
            valid = true,
            premiumUntil = end,
            trafficLeft = -1,
            trafficUnlimited = premium,
            statusText = buildString {
                append(if (premium) "Premium/Access" else freeStatusText)
                if (cdnGb != null && cdnGb > 0) append(" · CDN-Guthaben ${"%.1f".format(Locale.GERMANY, cdnGb)} GB")
            }
        )
    }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            val key = account?.plainApiKey ?: throw HosterException(
                "1fichier benötigt einen API-Key (unter Konten hinzufügen).",
                permanent = true
            )
            val link = normalize(url) ?: throw HosterException("Ungültiger 1fichier-Link", true)
            var fileName: String? = null
            var size = -1L
            try {
                val info = post("file/info.cgi", key, JSONObject().put("url", link))
                fileName = info.optString("filename").ifBlank { null }
                size = info.optLong("size", -1)
            } catch (e: AuthException) {
                // Ungueltiger API-Key: get_token wuerde genauso scheitern
                throw e
            } catch (_: Exception) {
                // Name/Groesse sind optional, ein Fehler hier verhindert den Download nicht
            }
            val token = post("download/get_token.cgi", key, JSONObject().put("url", link))
            val direct = token.optString("url")
            if (direct.isBlank()) throw HosterException("1fichier lieferte keine Download-URL", true)
            // Die von 1fichier gelieferte Pruefsumme ist Whirlpool (128 Hex), kein
            // SHA-1/MD5 - fuer die Integritaetspruefung daher nicht verwendbar.
            ResolvedLink(direct, fileName, size, hash = null)
        }
}
