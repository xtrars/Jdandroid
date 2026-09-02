package com.jdandroid.hoster

import com.jdandroid.data.Account
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class AccountType { USERNAME_PASSWORD, API_KEY }

data class ResolvedLink(
    val directUrl: String,
    val fileName: String? = null,
    val fileSize: Long = -1,
    /** Pruefsumme als Hex (MD5 = 32, SHA-1 = 40 Zeichen), falls der Hoster eine liefert. */
    val hash: String? = null
)

data class AccountInfo(
    val valid: Boolean,
    val premiumUntil: Long = 0,
    val trafficLeft: Long = -1,
    val statusText: String
)

/**
 * permanent = true: erneuter Versuch ohne Nutzeraktion ist sinnlos
 * (Datei offline, kein Premium, ungueltiger Account).
 */
class HosterException(message: String, val permanent: Boolean = false) : Exception(message)

interface Hoster {
    val id: String
    val displayName: String
    val accountType: AccountType

    /** Hinweis fuer den Account-Dialog, z.B. wo der API-Key zu finden ist. */
    val accountHint: String

    /**
     * Login-Seite fuer die Anmeldung im eingebetteten Browser. Nicht null,
     * wenn der Hoster ein CAPTCHA verlangt und headless nicht anmeldbar ist;
     * die Session-Cookies werden dann aus dem Browser uebernommen.
     */
    val webLoginUrl: String? get() = null

    fun matches(url: String): Boolean

    /** Prueft Zugangsdaten und liefert Premium-Status. */
    suspend fun checkAccount(account: Account): AccountInfo

    /** Loest einen Hoster-Link in eine direkte Download-URL auf. */
    suspend fun resolve(url: String, account: Account?): ResolvedLink
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    const val USER_AGENT =
        "Mozilla/5.0 (Android) JDAndroid/1.1"

    /** Obergrenze fuer als Text gelesene Antworten (API-Antworten sind klein). */
    const val MAX_TEXT_BYTES = 2L * 1024 * 1024

    /**
     * Liest eine Antwort als Text - aber nie unbegrenzt: antwortet ein Server
     * unerwartet mit einer Datei statt JSON, wuerde ein ungebremstes string()
     * den gesamten Inhalt in den Speicher laden (OutOfMemoryError).
     */
    fun get(url: String): String = client.newCall(
        Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    ).execute().use { resp ->
        if (resp.body == null) throw HosterException("Leere Antwort vom Server")
        resp.peekBody(MAX_TEXT_BYTES).string()
    }
}

object HosterRegistry {
    val hosters: List<Hoster> = listOf(
        RapidgatorHoster(),
        OneFichierHoster(),
        DdownloadHoster()
    )

    fun byId(id: String): Hoster? = hosters.find { it.id == id }

    fun forUrl(url: String): Hoster? = hosters.find { it.matches(url) }
}

object LinkParser {
    private val urlRegex = Regex("""https?://\S+""")

    /** Extrahiert alle unterstuetzten Links aus beliebigem Text. */
    fun parse(text: String): List<Pair<String, Hoster>> =
        urlRegex.findAll(text)
            .map { it.value.trimEnd(')', ']', '>', '.', ',', ';', '"', '\'') }
            .distinct()
            .mapNotNull { url -> HosterRegistry.forUrl(url)?.let { url to it } }
            .toList()
}
