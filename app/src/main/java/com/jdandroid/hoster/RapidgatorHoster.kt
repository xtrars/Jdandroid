package com.jdandroid.hoster

import com.jdandroid.data.Account
import com.jdandroid.data.plainPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

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

    /**
     * API-Aufruf per POST mit Formular-Body: Zugangsdaten und Token gehoeren
     * nicht in die URL (Proxy-/Server-Logs). Antwort begrenzt lesen wie Http.get.
     */
    private fun post(url: String, form: Map<String, String>): String {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .post(body)
            .build()
        return Http.client.newCall(request).execute().use { resp ->
            if (resp.body == null) throw HosterException("Leere Antwort vom Server")
            resp.peekBody(Http.MAX_TEXT_BYTES).string()
        }
    }

    /** Vollstaendige JSON-Antwort inklusive "status", ohne Auswertung. */
    private fun callRaw(path: String, params: Map<String, String>): JSONObject {
        val text = post("$base/$path", params)
        return runCatching { JSONObject(text) }
            .getOrElse { throw HosterException("Rapidgator: unerwartete Antwort") }
    }

    /**
     * Fehler aus einer API-Antwort mit status != 200 werfen.
     * [loginCall]: 401 beim Login heisst falsches Passwort/2FA - permanent,
     * damit weder Engine noch Kontopruefung in eine Login-Schleife laufen.
     * Bei anderen Aufrufen ist 401 ein abgelaufener Token und nach erneutem
     * Login behebbar, daher nicht permanent.
     */
    private fun fail(json: JSONObject, loginCall: Boolean): Nothing {
        val status = json.optInt("status")
        val details = json.optString("details").ifBlank { "HTTP $status" }
        val permanent = status in listOf(402, 403, 404) || (loginCall && status == 401)
        throw HosterException("Rapidgator: $details", permanent = permanent)
    }

    private fun call(path: String, params: Map<String, String>, loginCall: Boolean = false): JSONObject {
        val json = callRaw(path, params)
        if (json.optInt("status") != 200) fail(json, loginCall)
        return json.optJSONObject("response") ?: JSONObject()
    }

    /** Login; liefert Token und das "user"-Objekt der Login-Antwort. */
    private fun login(account: Account): Pair<String, JSONObject> {
        val user = account.username ?: throw HosterException("Kein Benutzername hinterlegt", true)
        val pass = account.plainPassword ?: throw HosterException("Kein Passwort hinterlegt", true)
        val resp = call("user/login", mapOf("login" to user, "password" to pass), loginCall = true)
        val token = resp.optString("token")
        if (token.isBlank()) throw HosterException("Rapidgator-Login fehlgeschlagen", true)
        tokens[account.id] = token
        return token to (resp.optJSONObject("user") ?: JSONObject())
    }

    private fun tokenFor(account: Account): String = tokens[account.id] ?: login(account).first

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        // Mit vorhandenem Token reicht user/info; nur bei 401 (Token abgelaufen)
        // neu anmelden. Ohne Token liefert die Login-Antwort das user-Objekt
        // bereits mit - ein zweiter Aufruf ist unnoetig.
        val cached = tokens[account.id]
        val user: JSONObject = if (cached != null) {
            val json = callRaw("user/info", mapOf("token" to cached))
            when (json.optInt("status")) {
                200 -> json.optJSONObject("response")?.optJSONObject("user") ?: JSONObject()
                401 -> {
                    tokens.remove(account.id)
                    login(account).second
                }
                else -> fail(json, loginCall = false)
            }
        } else {
            login(account).second
        }
        val premiumEnd = user.optLong("premium_end_time", 0) * 1000
        val premium = user.optBoolean("is_premium", false) ||
            premiumEnd > System.currentTimeMillis() ||
            user.optString("state_label").contains("Premium", ignoreCase = true)
        val traffic = user.optJSONObject("traffic")
        // "left"/"total" in Byte; null bei Konten ohne Tageslimit
        val trafficLeft = traffic?.takeUnless { it.isNull("left") }?.optLong("left", -1) ?: -1
        val trafficTotal = traffic?.takeUnless { it.isNull("total") }?.optLong("total", -1) ?: -1
        AccountInfo(
            valid = true,
            premiumUntil = premiumEnd,
            trafficLeft = trafficLeft,
            trafficTotal = trafficTotal,
            trafficUnlimited = premium && trafficLeft < 0 && trafficTotal < 0,
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
            // Login getrennt behandeln: ein Kontoproblem ist kein "Datei offline"
            val token = try {
                tokenFor(account)
            } catch (e: Exception) {
                return@withContext LinkInfo(online = null, note = e.message ?: "Rapidgator-Login fehlgeschlagen")
            }
            try {
                val file = call("file/info", mapOf("file_id" to id, "token" to token))
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
            // Nur wenn file/download mit einem vorhandenen Token scheitert
            // (Token abgelaufen), einmal neu anmelden. Schlaegt der Login selbst
            // fehl, wird der Fehler direkt weitergereicht - kein Login-Loop.
            val cached = tokens[account.id]
            if (cached != null) {
                try {
                    return@withContext attempt(cached)
                } catch (e: HosterException) {
                    // Bei permanenten Fehlern (Datei offline, kein Premium) ist ein
                    // erneuter Login sinnlos
                    if (e.permanent) throw e
                    tokens.remove(account.id)
                }
            }
            attempt(login(account).first)
        }
}
