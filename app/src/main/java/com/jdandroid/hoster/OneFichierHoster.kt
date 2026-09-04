package com.jdandroid.hoster

import com.jdandroid.data.Account
import com.jdandroid.data.plainApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 1fichier.com – offizielle REST-API (API-Key, Premium/Access erforderlich).
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

    override fun matches(url: String) = pattern.containsMatchIn(url)

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
            if (resp.body == null) throw HosterException("Leere Antwort von 1fichier")
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
                append(if (premium) "Premium/Access" else "Free (Downloads nicht möglich)")
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
