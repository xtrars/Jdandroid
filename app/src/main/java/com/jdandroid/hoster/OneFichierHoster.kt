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
    private val pattern = Regex("""https?://(?:www\.)?1fichier\.com/\?\S+""")

    override fun matches(url: String) = pattern.containsMatchIn(url)

    private fun post(path: String, apiKey: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$base/$path")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", Http.USER_AGENT)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val text = Http.client.newCall(request).execute().use { resp ->
            if (resp.body == null) throw HosterException("Leere Antwort von 1fichier")
            // begrenzt lesen, siehe Http.get
            resp.peekBody(Http.MAX_TEXT_BYTES).string()
        }
        val json = JSONObject(text)
        if (json.optString("status") == "KO") {
            val msg = json.optString("message").ifBlank { "Unbekannter Fehler" }
            // Flood/Rate-Limit ist voruebergehend; fehlende/geloeschte Datei permanent
            val transient = msg.contains("Flood", true) || msg.contains("try again", true)
            val permanent = !transient && (
                msg.contains("not found", true) ||
                    msg.contains("deleted", true) ||
                    msg.contains("no such", true) ||
                    msg.contains("not allowed", true) ||
                    msg.contains("Resource not", true)
            )
            throw HosterException("1fichier: $msg", permanent = permanent)
        }
        return json
    }

    /**
     * Oeffentlicher Link-Check ohne API-Key: check_links.pl liefert je Zeile
     * "url;dateiname;groesse;STATUS" (STATUS leer/OK = online, sonst z.B. NOT FOUND).
     */
    internal fun parseCheckLine(line: String): LinkInfo {
        val parts = line.trim().split(';')
        if (parts.size < 4) return LinkInfo(online = null, note = "Unerwartete Antwort")
        val name = parts[1].ifBlank { null }
        val size = parts[2].toLongOrNull() ?: -1
        val status = parts[3].trim()
        val offline = status.contains("NOT FOUND", true) || status.contains("BAD LINK", true) ||
            status.contains("DELETED", true)
        return if (offline) LinkInfo(online = false, note = status.lowercase().replaceFirstChar { it.uppercase() })
        else LinkInfo(online = true, fileName = name, fileSize = size)
    }

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            val form = okhttp3.FormBody.Builder().add("links[]", url).build()
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
        // 1fichier begrenzt Premium/Access-Downloads nicht; CDN-Guthaben (GB) nur als Hinweis
        val cdnGb = json.optDouble("cdn", -1.0).takeIf { it >= 0 }
            ?: json.optDouble("available_credits_in_gb", -1.0).takeIf { it >= 0 }
        AccountInfo(
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
            var fileName: String? = null
            var size = -1L
            var checksum: String? = null
            runCatching {
                val info = post("file/info.cgi", key, JSONObject().put("url", url))
                fileName = info.optString("filename").ifBlank { null }
                size = info.optLong("size", -1)
                // SHA-1 der Datei, fuer die Integritaetspruefung nach dem Download
                checksum = info.optString("checksum").lowercase().takeIf { it.length == 40 }
            }
            val token = post("download/get_token.cgi", key, JSONObject().put("url", url))
            val direct = token.optString("url")
            if (direct.isBlank()) throw HosterException("1fichier lieferte keine Download-URL", true)
            ResolvedLink(direct, fileName, size, checksum)
        }
}
