package com.jdandroid.hoster

import com.jdandroid.data.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ddownload.com – offizielle API (XFileSharing), API-Key aus dem Konto,
 * direkte Links nur mit Premium.
 */
class DdownloadHoster : Hoster {

    override val id = "ddownload"
    override val displayName = "ddownload"
    override val accountType = AccountType.API_KEY
    override val accountHint =
        "API-Key aus den ddownload.com-Kontoeinstellungen (my.ddownload.com, Bereich \"API\")."

    private val base = "https://api-v2.ddownload.com/api"
    private val pattern =
        Regex("""https?://(?:www\.)?(?:ddownload\.com|ddl\.to)/(?:f/)?([A-Za-z0-9]{6,20})""")

    override fun matches(url: String) = pattern.containsMatchIn(url)

    private fun fileCode(url: String): String =
        pattern.find(url)?.groupValues?.get(1)
            ?: throw HosterException("Ungültiger ddownload-Link", true)

    private fun call(path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val json = JSONObject(Http.get("$base/$path?$query"))
        val status = json.optInt("status")
        if (status != 200) {
            val msg = json.optString("msg").ifBlank { "HTTP $status" }
            throw HosterException("ddownload: $msg", permanent = status in listOf(400, 403, 404))
        }
        return json
    }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val key = account.apiKey ?: throw HosterException("Kein API-Key hinterlegt", true)
        val result = call("account/info", mapOf("key" to key)).optJSONObject("result")
            ?: throw HosterException("ddownload: unerwartete Antwort", true)
        val expireText = result.optString("premium_expire")
        val expire = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(expireText)?.time ?: 0L
        }.getOrDefault(0L)
        val premium = expire > System.currentTimeMillis()
        AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = result.optLong("traffic_left", -1),
            statusText = if (premium) "Premium" else "Free (Downloads nicht möglich)"
        )
    }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            val key = account?.apiKey ?: throw HosterException(
                "ddownload benötigt einen API-Key (unter Konten hinzufügen).",
                permanent = true
            )
            val code = fileCode(url)
            var fileName: String? = null
            runCatching {
                val info = call("file/info", mapOf("key" to key, "file_code" to code))
                    .optJSONArray("result")?.optJSONObject(0)
                fileName = info?.optString("name")?.ifBlank { null }
                if (info != null && info.optInt("status") == 404) {
                    throw HosterException("Datei ist offline", true)
                }
            }.onFailure { if (it is HosterException && it.permanent) throw it }
            val result = call("file/direct_link", mapOf("key" to key, "file_code" to code))
                .optJSONObject("result")
                ?: throw HosterException("ddownload lieferte keine Download-URL", true)
            val direct = result.optString("url")
            if (direct.isBlank()) throw HosterException("ddownload lieferte keine Download-URL", true)
            ResolvedLink(direct, fileName, result.optLong("size", -1))
        }
}
