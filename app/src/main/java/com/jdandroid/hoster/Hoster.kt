package com.jdandroid.hoster

import com.jdandroid.core.Texts
import com.jdandroid.data.Account
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

enum class AccountType { USERNAME_PASSWORD, API_KEY }

data class ResolvedLink(
    val directUrl: String,
    val fileName: String? = null,
    val fileSize: Long = -1,
    /** Pruefsumme als Hex (MD5 = 32, SHA-1 = 40 Zeichen), falls der Hoster eine liefert. */
    val hash: String? = null,
    /** Zusaetzliche Header fuer den Dateiabruf (z.B. Cookie im Free-Modus). */
    val headers: Map<String, String> = emptyMap()
)

/**
 * Ergebnis der Online-Pruefung im Linksammler. [online] null = nicht
 * pruefbar (z.B. Konto noetig), [note] erklaert das dem Nutzer.
 */
data class LinkInfo(
    val online: Boolean?,
    val fileName: String? = null,
    val fileSize: Long = -1,
    val note: String? = null
)

data class AccountInfo(
    val valid: Boolean,
    val premiumUntil: Long = 0,
    /** Verbleibender Traffic in Byte, -1 = unbekannt. */
    val trafficLeft: Long = -1,
    /** Gesamtkontingent in Byte (fuer den Balken), -1 = unbekannt. */
    val trafficTotal: Long = -1,
    /** Hoster ohne Traffic-Begrenzung (z.B. 1fichier Premium). */
    val trafficUnlimited: Boolean = false,
    val statusText: String
)

/**
 * permanent = true: erneuter Versuch ohne Nutzeraktion ist sinnlos
 * (Datei offline, kein Premium, ungueltiger Account).
 */
open class HosterException(message: String, val permanent: Boolean = false) : Exception(message)

/** Datei beim Hoster nicht (mehr) vorhanden - dauerhaft, ohne Textvergleich erkennbar. */
class FileOfflineException : HosterException(Texts.t("hoster_file_offline"), permanent = true)

/**
 * Free-Modus: der Hoster verlangt eine Wartezeit von [seconds] Sekunden,
 * bevor der Download (erneut) versucht werden darf. Nicht permanent - die
 * Engine reiht den Eintrag mit passendem retryAt wieder ein, ohne den
 * Versuch zu zaehlen.
 */
class WaitException(val seconds: Int, message: String) : HosterException(message, permanent = false)

/**
 * Free-Modus: ohne Captcha geht es nicht weiter. [pageUrl] ist die Seite, auf
 * der der Nutzer das Captcha im eingebetteten Browser loest; der dabei
 * abgefangene Direktlink kommt als [FreeHints.direktUrlAusBrowser] zurueck.
 *
 * Ein Hoster, der die Vorarbeit (Timer, Freischaltung) selbst erledigt und
 * nur das Captcha dem Browser ueberlaesst, gibt seine Session-Cookies mit:
 * [cookies] im Set-Cookie-Format ("name=wert; domain=…; path=/; secure"),
 * [cookieUrl] die Adresse, fuer die sie gelten. Die Captcha-Ansicht setzt
 * sie erst beim Oeffnen in ihren Browser - so ueberleben sie das Leeren der
 * Browser-Cookies durch andere Ansichten, und zwei wartende Eintraege
 * ueberschreiben sich nicht.
 */
class CaptchaRequiredException(
    val pageUrl: String,
    message: String,
    val cookieUrl: String? = null,
    val cookies: List<String> = emptyList()
) : HosterException(message, permanent = false)

/**
 * Hinweise fuer [Hoster.resolveFree]: ein bereits im Browser (Captcha-Ansicht)
 * abgefangener Direktlink samt den Browser-Cookies der Hoster-Domain
 * ("Name=Wert; ..."). Ohne Browser-Durchlauf sind beide null.
 */
data class FreeHints(
    val direktUrlAusBrowser: String? = null,
    val cookies: String? = null
)

/**
 * Zeitspannen in Hoster-Meldungen ("in 5 min"). Texts.t kennt keine
 * Plurals, daher Einheitenkuerzel: volle Stunden als h, volle Minuten als
 * min, sonst Sekunden.
 */
object HosterDurations {
    fun text(seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> Texts.t("hoster_duration_hours", seconds / 3600)
        seconds >= 60 && seconds % 60 == 0 -> Texts.t("hoster_duration_minutes", seconds / 60)
        else -> Texts.t("hoster_duration_seconds", seconds)
    }
}

/**
 * Standard fuer [Hoster.isDirectDownloadUrl]: Fileserver-Adressen liegen auf
 * einem anderen Host als der Hauptdomain und enden auf eine Dateiendung.
 * Seitenlinks, Skripte/Bilder und CGI-Aufrufe (tracker.cgi) fallen heraus.
 */
object DirectLinks {
    val assetExtensions = setOf(
        "html", "htm", "php", "css", "js", "png", "jpg", "jpeg", "gif",
        "svg", "ico", "woff", "woff2", "ttf", "webp", "json", "xml", "cgi"
    )

    fun isDirectDownloadUrl(url: String, siteHosts: Set<String>): Boolean {
        val http = url.toHttpUrlOrNull() ?: return false
        if (http.host.lowercase() in siteHosts) return false
        val last = http.pathSegments.lastOrNull().orEmpty()
        val ext = last.substringAfterLast('.', "").lowercase()
        return last.contains('.') && ext.isNotEmpty() && ext !in assetExtensions
    }

    /** True when the URL's host is one of [siteHosts] or a subdomain of one (file servers). */
    fun isSiteHost(url: String, siteHosts: Set<String>): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return siteHosts.any { host == it || host.endsWith(".$it") }
    }
}

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

    /**
     * Hauptdomain(s) der Webseite in Kleinschreibung (mit www-Varianten).
     * Grundlage fuer [isDirectDownloadUrl] und den Host-Filter der Captcha-Ansicht.
     */
    val siteHosts: Set<String>

    /**
     * true, sobald [resolveFree] umgesetzt ist. Steuert nur den Kontostatus
     * ([freeStatusText]); die Engine ruft [resolveFree] unabhaengig davon.
     */
    val supportsFree: Boolean get() = false

    /** Kontostatus eines Kontos ohne Premium. */
    val freeStatusText: String
        get() = if (supportsFree) Texts.t("hoster_free_status") else Texts.t("hoster_free_status_unsupported")

    fun matches(url: String): Boolean

    /** Prueft Zugangsdaten und liefert Premium-Status. */
    suspend fun checkAccount(account: Account): AccountInfo

    /** Loest einen Hoster-Link mit Premium-Konto in eine direkte Download-URL auf. */
    suspend fun resolve(url: String, account: Account?): ResolvedLink

    /**
     * Free-Modus (kein Konto): Direktlink ohne Premium. Darf [WaitException]
     * (Wartezeit) und [CaptchaRequiredException] (Captcha im Browser noetig)
     * werfen; mit [FreeHints.direktUrlAusBrowser] liegt der Link bereits vor
     * und ist nur noch zu uebernehmen (ggf. mit [FreeHints.cookies] als
     * [ResolvedLink.headers]). Standard: nicht unterstuetzt (permanent).
     */
    suspend fun resolveFree(url: String, hints: FreeHints): ResolvedLink =
        throw HosterException(Texts.t("hoster_free_unsupported"), true)

    /**
     * Erkennt in der Captcha-Ansicht die Navigation auf den Fileserver: diese
     * Adresse wird abgefangen und der Engine als Direktlink uebergeben.
     */
    fun isDirectDownloadUrl(url: String): Boolean = DirectLinks.isDirectDownloadUrl(url, siteHosts)

    /**
     * Prueft ohne Download, ob die Datei online ist, und liefert wenn moeglich
     * Name und Groesse (Linksammler). Standard: nicht pruefbar.
     */
    suspend fun checkLink(url: String, account: Account?): LinkInfo =
        LinkInfo(online = null, note = Texts.t("hoster_no_check_possible"))
}

object Http {
    /**
     * Kennung der System-WebView, beim App-Start ermittelt. Cloudflare bindet
     * das Cookie cf_clearance an die Kennung, mit der es ausgestellt wurde -
     * eine per Browser-Login uebernommene Session gilt daher nur mit
     * derselben Kennung.
     */
    @Volatile
    var browserUserAgent: String? = null

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor(CrossSiteRedirectInterceptor)
        .build()

    /** Same host or same parent domain (s12.ddownload.com and ddownload.com). */
    fun sameSite(a: HttpUrl, b: HttpUrl): Boolean {
        val ha = a.host.lowercase()
        val hb = b.host.lowercase()
        if (ha == hb) return true
        val domain = parentDomain(ha) ?: return false
        return domain == parentDomain(hb)
    }

    /** Last two labels of a host name; none for IP addresses and single labels. */
    private fun parentDomain(host: String): String? {
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        return labels.takeLast(2).joinToString(".")
    }

    // Ohne Versionsnummer: sie lief der App-Version davon (versionName in build.gradle.kts)
    const val USER_AGENT = "Mozilla/5.0 (Android) JDAndroid"

    /**
     * Obergrenze fuer als Text gelesene Antworten (API-Antworten sind klein).
     * Antworten immer mit peekBody(MAX_TEXT_BYTES) lesen: antwortet ein Server
     * unerwartet mit einer Datei statt JSON, wuerde ein ungebremstes string()
     * den gesamten Inhalt in den Speicher laden (OutOfMemoryError).
     */
    const val MAX_TEXT_BYTES = 2L * 1024 * 1024
}

/**
 * OkHttp copies Cookie and Referer of the original request onto every
 * redirect hop (only Authorization is dropped on a host change); a file
 * server redirecting to a foreign CDN would receive the hoster's session.
 */
internal object CrossSiteRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (Http.sameSite(chain.call().request().url, request.url)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().removeHeader("Cookie").removeHeader("Referer").build())
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
    /** Anfuehrungszeichen, spitze und eckige Klammern beenden eine URL. */
    private val urlRegex = Regex("""https?://[^\s"'<>\[\]]+""")

    /** Extrahiert alle unterstuetzten Links aus beliebigem Text. */
    fun parse(text: String): List<Pair<String, Hoster>> =
        urlRegex.findAll(text)
            // Kommas und Semikola trennen Links ("url1,url2"): Teile ohne http verwerfen
            .flatMap { m -> m.value.split(',', ';').asSequence().filter { it.startsWith("http") } }
            .map { it.trimEnd(')', ']', '>', '.', ',', ';', '"', '\'') }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { url -> HosterRegistry.forUrl(url)?.let { url to it } }
            .toList()
}
