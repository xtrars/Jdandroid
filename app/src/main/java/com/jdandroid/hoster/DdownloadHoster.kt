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

    /** Browsertypischer User-Agent: XFileSharing/Cloudflare mögen keine Bot-Kennungen. */
    private val browserUa =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    /** Ein Cookie-Speicher (Session) pro Account-Id. */
    private val cookieStores = HashMap<Long, MutableList<Cookie>>()
    private val clients = HashMap<Long, OkHttpClient>()

    override fun matches(url: String) = pattern.containsMatchIn(url)

    private fun fileCode(url: String): String =
        pattern.find(url)?.groupValues?.get(1)
            ?: throw HosterException("Ungültiger ddownload-Link", true)

    private data class Resp(val code: Int, val body: String)

    private fun clientFor(accountId: Long): OkHttpClient = clients.getOrPut(accountId) {
        val store = cookieStores.getOrPut(accountId) { mutableListOf() }
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    for (c in cookies) {
                        store.removeAll { it.name == c.name && it.domain == c.domain }
                        store.add(c)
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    store.filter { it.matches(url) }
            })
            .build()
    }

    private fun OkHttpClient.fetch(
        url: String,
        form: Map<String, String>? = null,
        referer: String? = null
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
        return newCall(builder.build()).execute().use { resp ->
            Resp(resp.code, resp.body?.string() ?: "")
        }
    }

    /** Erkennt Cloudflare-/WAF-Blockaden, damit die Meldung nicht "falsches Passwort" lautet. */
    private fun checkBlocked(resp: Resp) {
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
     * Loggt ein (falls nötig) und liefert Client plus HTML der Kontoseite.
     * Verifiziert das Ergebnis, statt sich auf einen Cookie-Namen zu verlassen.
     */
    private fun loginAndFetchAccount(account: Account): Pair<OkHttpClient, String> {
        val user = account.username ?: throw HosterException("Kein Benutzername hinterlegt", true)
        val pass = account.password ?: throw HosterException("Kein Passwort hinterlegt", true)
        val client = clientFor(account.id)

        // Bestehende Session weiterverwenden
        val existing = runCatching { client.fetch("$siteBase/?op=my_account", referer = siteBase) }
            .getOrNull()
        if (existing != null && isLoggedIn(existing.body)) return client to existing.body

        // 1) Login-Seite holen: setzt Basis-Cookies und liefert versteckte Felder
        val loginPage = client.fetch("$siteBase/login.html", referer = siteBase)
        checkBlocked(loginPage)

        // 2) Versteckte Felder des Login-Formulars uebernehmen (op, token, rand ...)
        val form = hiddenInputs(loginPage.body).toMutableMap()
        form["op"] = "login"
        form["login"] = user
        form["password"] = pass
        form.putIfAbsent("redirect", "")

        val posted = client.fetch("$siteBase/", form = form, referer = "$siteBase/login.html")
        checkBlocked(posted)

        if (posted.body.contains("Incorrect Login or Password", true) ||
            posted.body.contains("Wrong password", true) ||
            posted.body.contains("Login or password incorrect", true)
        ) {
            throw HosterException(
                "ddownload: Benutzername oder Passwort falsch", permanent = true
            )
        }

        // 3) Ergebnis pruefen
        val accountPage = client.fetch("$siteBase/?op=my_account", referer = siteBase)
        checkBlocked(accountPage)
        if (!isLoggedIn(accountPage.body)) {
            val cookieNames = cookieStores[account.id]?.joinToString(",") { it.name }.orEmpty()
            throw HosterException(
                "ddownload: Login nicht bestätigt (HTTP ${posted.code}" +
                    (if (cookieNames.isNotBlank()) ", Cookies: $cookieNames" else ", keine Cookies") +
                    "). Zugangsdaten prüfen; bei aktiver Zwei-Faktor-Anmeldung wird der " +
                    "Login nicht unterstützt.",
                permanent = false
            )
        }
        return client to accountPage.body
    }

    /** Alle hidden-Felder eines Formulars einsammeln (Reihenfolge der Attribute egal). */
    private fun hiddenInputs(html: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val t = tag.value
            val name = Regex("""\bname=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(1) ?: return@forEach
            val value = Regex("""\bvalue=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(t)?.groupValues?.get(1) ?: ""
            result[name] = value
        }
        return result
    }

    override suspend fun checkAccount(account: Account): AccountInfo = withContext(Dispatchers.IO) {
        val (_, html) = loginAndFetchAccount(account)

        val expire = Regex(
            """[Pp]remium[^<:]*expire[^:<]*:?\s*(?:</?[^>]*>\s*)*([0-9]{1,2}\s+\w+\s+[0-9]{4})"""
        ).find(html)?.groupValues?.get(1)?.let { parseDate(it) } ?: 0L

        val premiumWord = Regex("""[Pp]remium""").containsMatchIn(html) &&
            !Regex("""Account type[^<]*(?:</?[^>]*>\s*)*Free""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)
        val premium = expire > System.currentTimeMillis() || (expire == 0L && premiumWord)

        val trafficLeft = Regex(
            """[Tt]raffic\s+available[^:]*:?\s*(?:</?[^>]*>\s*)*([\d.]+)\s*(GB|MB|TB)"""
        ).find(html)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: -1L

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
            val (client, _) = loginAndFetchAccount(account)
            val code = fileCode(url)
            val pageUrl = "$siteBase/$code"

            var page = client.fetch(pageUrl, referer = siteBase)
            checkBlocked(page)
            checkOffline(page.body)

            var direct = extractDirectLink(page.body)
            if (direct == null) {
                val form = downloadForm(page.body) ?: throw HosterException(
                    "ddownload: Download-Formular nicht gefunden (kein Premium?)", true
                )
                page = client.fetch(pageUrl, form = form, referer = pageUrl)
                checkBlocked(page)
                checkOffline(page.body)
                direct = extractDirectLink(page.body)
            }
            if (direct.isNullOrBlank()) {
                throw HosterException(
                    "ddownload lieferte keine Download-URL (Premium-Konto nötig?)", true
                )
            }
            val fileName = Regex("""<h[12][^>]*>\s*(?:Download\s+File\s*)?([^<]+?)\s*</h[12]>""")
                .find(page.body)?.groupValues?.get(1)?.trim()?.ifBlank { null }
                ?: direct.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }
            ResolvedLink(direct, fileName)
        }

    private fun checkOffline(html: String) {
        if (html.contains("File Not Found", true) ||
            html.contains("file was deleted", true) ||
            html.contains("No such file", true)
        ) {
            throw HosterException("Datei ist offline", true)
        }
    }

    /** Felder der Download-Form (op=download1 -> download2, Premium-Methode). */
    private fun downloadForm(html: String): Map<String, String>? {
        val inputs = hiddenInputs(html).toMutableMap()
        if (!inputs.containsKey("op")) return null
        if (inputs["op"] == "download1") inputs["op"] = "download2"
        inputs["method_premium"] = "1"
        inputs.remove("method_free")
        return inputs
    }

    private fun extractDirectLink(html: String): String? {
        Regex(
            """<a[^>]+href=["'](https?://[^"']+?/d/[^"']+|https?://[^"']*download[^"']*)["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.let { return it.groupValues[1] }
        Regex(
            """(https?://[^\s"'<>]+\.(?:rar|zip|7z|mkv|mp4|avi|iso|bin|exe|pdf)[^\s"'<>]*)""",
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
