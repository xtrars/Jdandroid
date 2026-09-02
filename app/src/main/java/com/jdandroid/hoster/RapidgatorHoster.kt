package com.jdandroid.hoster

import com.jdandroid.data.Account
import com.jdandroid.data.plainPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * rapidgator.net – offizielle API v2.
 * Download ueber die API setzt einen Premium-Account voraus.
 */
class RapidgatorHoster : Hoster {

    override val id = "rapidgator"
    override val displayName = "Rapidgator"
    override val accountType = AccountType.USERNAME_PASSWORD
    override val accountHint = "E-Mail und Passwort des Rapidgator-Kontos (Premium erforderlich)."

    private val base = "https://rapidgator.net/api/v2"
    private val pattern = Regex("""https?://(?:www\.)?(?:rapidgator\.net|rg\.to)/file/\S+""")

    /** Session-Token pro Account-Id zwischenspeichern. */
    private val tokens = java.util.concurrent.ConcurrentHashMap<Long, String>()

    override fun matches(url: String) = pattern.containsMatchIn(url)

    /** file_id (Hash) aus der Rapidgator-URL: .../file/<id>[/name.html] */
    private fun fileId(url: String): String =
        Regex("""/file/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: throw HosterException("Ungültiger Rapidgator-Link", true)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val json = JSONObject(Http.get("$base/$path?$query"))
        val status = json.optInt("status")
        if (status != 200) {
            val details = json.optString("details").ifBlank { "HTTP $status" }
            // 401 (Token abgelaufen) ist nach erneutem Login behebbar, daher nicht permanent
            throw HosterException("Rapidgator: $details", permanent = status in listOf(402, 403, 404))
        }
        return json.optJSONObject("response") ?: JSONObject()
    }

    private fun login(account: Account): String {
        val user = account.username ?: throw HosterException("Kein Benutzername hinterlegt", true)
        val pass = account.plainPassword ?: throw HosterException("Kein Passwort hinterlegt", true)
        val resp = call("user/login", mapOf("login" to user, "password" to pass))
        val token = resp.optString("token")
        if (token.isBlank()) throw HosterException("Rapidgator-Login fehlgeschlagen", true)
        tokens[account.id] = token
        return token
    }

    private fun tokenFor(account: Account): String = tokens[account.id] ?: login(account)

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val token = login(account)
        val resp = call("user/info", mapOf("token" to token))
        val user = resp.optJSONObject("user") ?: JSONObject()
        val premiumEnd = user.optLong("premium_end_time", 0) * 1000
        val premium = user.optBoolean("is_premium", false) ||
            premiumEnd > System.currentTimeMillis() ||
            user.optString("state_label").contains("Premium", ignoreCase = true)
        val trafficLeft = user.optJSONObject("traffic")?.optLong("left", -1) ?: -1
        AccountInfo(
            valid = true,
            premiumUntil = premiumEnd,
            trafficLeft = trafficLeft,
            statusText = if (premium) "Premium" else "Free (Downloads nicht möglich)"
        )
    }

    override suspend fun checkLink(url: String, account: Account?): LinkInfo =
        withContext(Dispatchers.IO) {
            // Die API verlangt auch fuer file/info eine Session
            if (account == null) {
                return@withContext LinkInfo(online = null, note = "Prüfung erst mit Rapidgator-Konto")
            }
            val id = fileId(url)
            try {
                val file = call("file/info", mapOf("file_id" to id, "token" to tokenFor(account)))
                    .optJSONObject("file")
                    ?: return@withContext LinkInfo(online = false, note = "Datei nicht gefunden")
                LinkInfo(
                    online = true,
                    fileName = file.optString("name").ifBlank { null },
                    fileSize = file.optLong("size", -1)
                )
            } catch (e: HosterException) {
                if (e.permanent) LinkInfo(online = false, note = e.message)
                else {
                    // Token abgelaufen: einmal neu anmelden
                    tokens.remove(account.id)
                    LinkInfo(online = null, note = e.message)
                }
            }
        }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            if (account == null) {
                throw HosterException(
                    "Rapidgator benötigt einen Premium-Account (unter Konten hinzufügen).",
                    permanent = true
                )
            }
            val id = fileId(url)
            val attempt: (String) -> ResolvedLink = { token ->
                val resp = call("file/download", mapOf("file_id" to id, "token" to token))
                val direct = resp.optString("download_url")
                if (direct.isBlank()) throw HosterException("Rapidgator lieferte keine Download-URL", true)
                // Name, Groesse und MD5 fuer die Integritaetspruefung; optional,
                // ein Fehler hier darf den Download nicht verhindern.
                val info = runCatching {
                    call("file/info", mapOf("file_id" to id, "token" to token)).optJSONObject("file")
                }.getOrNull()
                ResolvedLink(
                    directUrl = direct,
                    fileName = info?.optString("name")?.ifBlank { null },
                    fileSize = info?.optLong("size", -1) ?: -1,
                    hash = info?.optString("hash")?.lowercase()?.takeIf { it.length == 32 }
                )
            }
            try {
                attempt(tokenFor(account))
            } catch (e: HosterException) {
                // Bei permanenten Fehlern (Datei offline, kein Premium) ist ein
                // erneuter Login sinnlos
                if (e.permanent) throw e
                tokens.remove(account.id)
                attempt(login(account))
            }
        }
}
