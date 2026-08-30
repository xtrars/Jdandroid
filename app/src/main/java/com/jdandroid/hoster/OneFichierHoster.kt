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
        AccountInfo(
            valid = true,
            premiumUntil = end,
            trafficLeft = -1,
            statusText = if (premium) "Premium/Access" else "Free (Downloads nicht möglich)"
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
            runCatching {
                val info = post("file/info.cgi", key, JSONObject().put("url", url))
                fileName = info.optString("filename").ifBlank { null }
                size = info.optLong("size", -1)
            }
            val token = post("download/get_token.cgi", key, JSONObject().put("url", url))
            val direct = token.optString("url")
            if (direct.isBlank()) throw HosterException("1fichier lieferte keine Download-URL", true)
            ResolvedLink(direct, fileName, size)
        }
}
