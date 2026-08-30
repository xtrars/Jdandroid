package com.jdandroid.hoster

import com.jdandroid.data.Account
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
    override val accountType = AccountType.USERNAME_PASSWORD
    override val accountHint =
        "Benutzername (oder E-Mail) und Passwort des ddownload.com-Kontos. " +
            "Für Downloads ist ein Premium-Konto erforderlich."

    private val siteBase = "https://ddownload.com"
    private val pattern =
        Regex("""https?://(?:www\.)?(?:ddownload\.com|ddl\.to)/(?:f/|d/)?([A-Za-z0-9]{6,20})""")

    /** Ein Cookie-Speicher (Session) pro Account-Id. */
    private val cookieStores = HashMap<Long, MutableList<Cookie>>()

    override fun matches(url: String) = pattern.containsMatchIn(url)

    private fun fileCode(url: String): String =
        pattern.find(url)?.groupValues?.get(1)
            ?: throw HosterException("Ungültiger ddownload-Link", true)

    private fun clientFor(accountId: Long): OkHttpClient {
        val store = cookieStores.getOrPut(accountId) { mutableListOf() }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    for (c in cookies) {
                        store.removeAll { it.name == c.name }
                        store.add(c)
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    store.filter { it.matches(url) }
            })
            .build()
    }

    private fun OkHttpClient.getText(url: String): String = newCall(
        Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
    ).execute().use { it.body?.string() ?: "" }

    private fun OkHttpClient.postText(url: String, form: Map<String, String>): String {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return newCall(
            Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).post(body).build()
        ).execute().use { it.body?.string() ?: "" }
    }

    /** Loggt ein und wirft bei falschen Zugangsdaten. Liefert den Client mit Session. */
    private fun login(account: Account): OkHttpClient {
        val user = account.username ?: throw HosterException("Kein Benutzername hinterlegt", true)
        val pass = account.password ?: throw HosterException("Kein Passwort hinterlegt", true)
        val client = clientFor(account.id)
        // vorhandene Session evtl. noch gültig – aber Login ist idempotent
        val html = client.postText(
            "$siteBase/",
            mapOf(
                "op" to "login",
                "login" to user,
                "password" to pass,
                "redirect" to "$siteBase/?op=my_account"
            )
        )
        if (html.contains("Incorrect Login or Password", ignoreCase = true) ||
            html.contains("Wrong password", ignoreCase = true)
        ) {
            throw HosterException("Login fehlgeschlagen: falscher Benutzername oder Passwort", true)
        }
        val loggedIn = cookieStores[account.id]?.any { it.name == "xfss" || it.name == "login" } == true
        if (!loggedIn) {
            throw HosterException("Login fehlgeschlagen (keine Session vom Server erhalten)", true)
        }
        return client
    }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val client = login(account)
        val html = client.getText("$siteBase/?op=my_account")

        // "Premium account expire: 15 January 2026" o.ä.
        val expire = Regex(
            """[Pp]remium[^<:]*expire[^:<]*:?\s*</?[^>]*>?\s*([0-9]{1,2}\s+\w+\s+[0-9]{4})"""
        ).find(html)?.groupValues?.get(1)?.let { parseDate(it) } ?: 0L

        val hasPremiumWord = Regex("""[Pp]remium[- ]?[Aa]ccount""").containsMatchIn(html) ||
            html.contains("premium account expire", ignoreCase = true)
        val premium = expire > System.currentTimeMillis() || (hasPremiumWord && expire == 0L)

        // Traffic, falls angezeigt ("Traffic available: 50 GB")
        val trafficLeft = Regex("""[Tt]raffic\s+available[^:]*:?\s*</?[^>]*>?\s*([\d.]+)\s*(GB|MB|TB)""")
            .find(html)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: -1L

        AccountInfo(
            valid = true,
            premiumUntil = expire,
            trafficLeft = trafficLeft,
            statusText = if (premium) "Premium" else "Free (Downloads nicht möglich)"
        )
    }

    override suspend fun resolve(url: String, account: Account?): ResolvedLink =
        withContext(Dispatchers.IO) {
            if (account == null) throw HosterException(
                "ddownload benötigt ein Premium-Konto (unter Konten hinzufügen).", true
            )
            val client = login(account)
            val code = fileCode(url)
            val pageUrl = "$siteBase/$code"

            var html = client.getText(pageUrl)
            checkOffline(html)

            // Premium liefert oft direkt einen Link; sonst zweistufige Form absenden.
            var direct = extractDirectLink(html)
            if (direct == null) {
                val form = extractForm(html) ?: throw HosterException(
                    "ddownload: Download-Formular nicht gefunden (kein Premium?)", true
                )
                html = client.postText(pageUrl, form)
                checkOffline(html)
                direct = extractDirectLink(html)
            }
            if (direct.isNullOrBlank()) {
                throw HosterException(
                    "ddownload lieferte keine Download-URL (Premium-Konto nötig?)", true
                )
            }
            val fileName = Regex("""<h[12][^>]*>\s*(?:Download\s+File\s*)?([^<]+?)\s*</h[12]>""")
                .find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }
                ?: direct.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }
            ResolvedLink(direct, fileName)
        }

    private fun checkOffline(html: String) {
        if (html.contains("File Not Found", ignoreCase = true) ||
            html.contains("file was deleted", ignoreCase = true) ||
            html.contains("No such file", ignoreCase = true)
        ) {
            throw HosterException("Datei ist offline", true)
        }
    }

    /** Sammelt die versteckten Formularfelder der Download-Form (op=download2 etc.). */
    private fun extractForm(html: String): Map<String, String>? {
        val inputs = Regex(
            """<input[^>]*\bname=["']([^"']+)["'][^>]*\bvalue=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html).associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()

        if (inputs.isEmpty() || !inputs.containsKey("op")) return null
        // Premium-Methode bevorzugen, sonst Standardablauf
        if (inputs["op"] == "download1") inputs["op"] = "download2"
        inputs["method_premium"] = "1"
        return inputs
    }

    /** Findet den finalen Direktlink im HTML. */
    private fun extractDirectLink(html: String): String? {
        // 1) expliziter Download-Button/Link
        Regex(
            """<a[^>]+href=["'](https?://[^"']+?/d/[^"']+|https?://[^"']*download[^"']*)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.let { return it.groupValues[1] }
        // 2) Link zu einer Datei-Endung auf einem Download-Host
        Regex(
            """(https?://[^\s"'<>]+\.(?:rar|zip|7z|mkv|mp4|avi|iso|part\d+\.rar|bin|exe|pdf)[^\s"'<>]*)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun parseDate(s: String): Long = runCatching {
        SimpleDateFormat("d MMMM yyyy", Locale.US).parse(s.trim())?.time ?: 0L
    }.getOrDefault(0L)

    private fun toBytes(value: String, unit: String): Long {
        val n = value.toDoubleOrNull() ?: return -1
        val factor = when (unit.uppercase()) {
            "TB" -> 1L shl 40
            "GB" -> 1L shl 30
            "MB" -> 1L shl 20
            else -> 1L
        }
        return (n * factor).toLong()
    }

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
}
