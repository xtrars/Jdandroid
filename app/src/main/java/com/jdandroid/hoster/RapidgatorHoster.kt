package com.jdandroid.hoster

import com.jdandroid.data.Account
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
    private val tokens = HashMap<Long, String>()

    override fun matches(url: String) = pattern.containsMatchIn(url)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val json = JSONObject(Http.get("$base/$path?$query"))
        val status = json.optInt("status")
        if (status != 200) {
            val details = json.optString("details").ifBlank { "HTTP $status" }
            throw HosterException("Rapidgator: $details", permanent = status in listOf(401, 402, 403, 404))
        }
        return json.optJSONObject("response") ?: JSONObject()
    }

    private fun login(account: Account): String {
        val user = account.username ?: throw HosterException("Kein Benutzername hinterlegt", true)
        val pass = account.password ?: throw HosterException("Kein Passwort hinterlegt", true)
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
        val premium = user.optBoolean("is_premium", false)
        val premiumEnd = user.optLong("premium_end_time", 0) * 1000
        val trafficLeft = user.optJSONObject("traffic")?.optLong("left", -1) ?: -1
        AccountInfo(
            valid = true,
            premiumUntil = premiumEnd,
            trafficLeft = trafficLeft,
            statusText = if (premium) "Premium" else "Free (Downloads nicht möglich)"
        )
    }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            if (account == null) {
                throw HosterException(
                    "Rapidgator benötigt einen Premium-Account (unter Konten hinzufügen).",
                    permanent = true
                )
            }
            val attempt: (String) -> ResolvedLink = { token ->
                val resp = call("file/download", mapOf("url" to url, "token" to token))
                val direct = resp.optString("download_url")
                if (direct.isBlank()) throw HosterException("Rapidgator lieferte keine Download-URL", true)
                ResolvedLink(direct)
            }
            try {
                attempt(tokenFor(account))
            } catch (e: HosterException) {
                // Token evtl. abgelaufen -> einmal neu einloggen
                tokens.remove(account.id)
                attempt(login(account))
            }
        }
}
