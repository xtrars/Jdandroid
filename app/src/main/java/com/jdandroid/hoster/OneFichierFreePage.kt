package com.jdandroid.hoster

/**
 * Einordnung eines Hinweistexts der 1fichier-Website im Free-Modus
 * (Dateiseite oder Antwort auf das Download-Formular).
 */
internal sealed class OneFichierBlock {
    /** Wartezeit in Sekunden (inklusive Reserve) mit deutscher Meldung. */
    data class Wait(val seconds: Int, val text: String) : OneFichierBlock()

    /** Ohne Konto nicht ladbar - erneuter Versuch ist sinnlos. */
    data class Permanent(val text: String) : OneFichierBlock()

    /** Voruebergehend, aber ohne feste Wartezeit (Engine-Wiederholung). */
    data class Transient(val text: String) : OneFichierBlock()
}

/** Das Download-Formular der Dateiseite (POST an dieselbe Adresse). */
internal data class OneFichierForm(
    val action: String?,
    val fields: Map<String, String>,
    /** Datei ist passwortgeschuetzt (Feld "pass"). */
    val needsPassword: Boolean
)

/**
 * Reine Auswertung der 1fichier-Dateiseite im Free-Modus (ohne Netz, ohne
 * Android), damit jede Regel gegen echte Seitenausschnitte pruefbar bleibt.
 *
 * Aufbau (Stand 09/2026, englische Sprache ueber `LG=en`): Name und Groesse
 * in einer Tabelle (`Filename :</td><td>…`, `Size :</td><td>1.2 GB`), das
 * Formular `<form action="https://1fichier.com/?<id>" method="post">` mit
 * verstecktem Zufallsfeld `adz`, der Countdown als `var count = 30;`. Nach
 * dem POST steht der Direktlink als `<a href="https://a-3.1fichier.com/…">
 * Click here to download`; Sperren stehen als sichtbarer Text ("You must
 * wait 5 minutes", "You already downloading a file"). Ein 404 kommt als
 * `<div class="notice alc">The requested file does not exist`.
 */
internal object OneFichierFreePage {

    private val ic = RegexOption.IGNORE_CASE

    /** Sichtbarer Text ohne Skripte, Stile, Kommentare und Tags. */
    fun visibleText(html: String): String =
        html.replace(Regex("""<script[\s\S]*?</script>""", ic), " ")
            .replace(Regex("""<style[\s\S]*?</style>""", ic), " ")
            .replace(Regex("""<!--[\s\S]*?-->"""), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")

    fun isOffline(html: String): Boolean =
        Regex(
            """>\s*File not found|The requested file (?:has been deleted|do(?:es)? not exist)|""" +
                """Le fichier demandé n'existe pas|Fichier introuvable""",
            ic
        ).containsMatchIn(html)

    fun fileName(html: String): String? =
        Regex("""(?:Filename|Nom du fichier)\s*:?\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*<""", ic)
            .find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("""<title>\s*(?:Download|Téléchargement)\s+([^<]+?)\s*</title>""", ic)
                .find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    /** Groesse in Byte (1024-basiert), -1 wenn nicht angegeben. */
    fun fileSize(html: String): Long {
        val m = Regex("""(?:Size|Taille)\s*:?\s*</td>\s*<td[^>]*>\s*([\d.,]+)\s*([KMGT]?[Bo])\b""", ic).find(html)
            ?: return -1
        return toBytes(m.groupValues[1], m.groupValues[2])
    }

    /** "1.5 GB", "1,5 Go", "700 MB" → Byte; franzoesische Einheit "o" (octet) wie "B". */
    fun toBytes(value: String, unit: String): Long {
        val number = value.replace(",", ".").toDoubleOrNull() ?: return -1
        val factor = when (unit.uppercase().first()) {
            'K' -> 1024.0
            'M' -> 1024.0 * 1024
            'G' -> 1024.0 * 1024 * 1024
            'T' -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (number * factor).toLong()
    }

    /**
     * Countdown vor dem Download-Knopf in Sekunden (`var count = 30;`,
     * Varianten `Free download in ⏳ 30`, `var ct = 30`), 0 = keiner.
     */
    fun countdownSeconds(html: String): Int {
        val patterns = listOf(
            """var\s+count\s*=\s*(\d+)\s*;""",
            """Free download in\s*(?:⏳|&#9203;|&#x23f3;)?\s*(\d+)""",
            """var\s+ct\s*=\s*(\d+)"""
        )
        for (p in patterns) {
            Regex(p, ic).find(html)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        }
        return 0
    }

    /**
     * Download-Formular: erstes POST-Formular, dessen Adresse `/?<id>` enthaelt
     * (oder `id="f1"`); ohne Kennung das erste POST-Formular. Alle versteckten
     * Felder werden uebernommen, `save` (in Konto speichern) entfernt.
     * null = kein Formular (Sperre, Hinweisseite).
     */
    fun downloadForm(html: String, id: String? = null): OneFichierForm? {
        val forms = Regex("""<form\b([^>]*)>([\s\S]*?)</form>""", ic).findAll(html).toList()
        val posts = forms.filter { Regex("""method\s*=\s*["']?post""", ic).containsMatchIn(it.groupValues[1]) }
        val chosen = posts.firstOrNull { m ->
            val attrs = m.groupValues[1]
            (id != null && Regex("""action\s*=\s*["'][^"']*\?$id(?:[&"']|$)""", ic).containsMatchIn(attrs)) ||
                Regex("""\bid\s*=\s*["']f1["']""", ic).containsMatchIn(attrs)
        } ?: posts.firstOrNull { m ->
            // Login- und Suchformulare ausschliessen: das Download-Formular hat kein Feld "mail"
            !Regex("""name\s*=\s*["']mail["']""", ic).containsMatchIn(m.groupValues[2])
        } ?: return null
        val attrs = chosen.groupValues[1]
        val body = chosen.groupValues[2]
        val action = Regex("""action\s*=\s*["']([^"']*)["']""", ic).find(attrs)?.groupValues?.get(1)
            ?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        val fields = LinkedHashMap<String, String>()
        Regex("""<input\b([^>]*)>""", ic).findAll(body).forEach { input ->
            val a = input.groupValues[1]
            val type = attr(a, "type")?.lowercase() ?: "text"
            val name = attr(a, "name") ?: return@forEach
            if (type == "hidden") fields[name] = attr(a, "value")?.let(::unescape) ?: ""
        }
        fields.remove("save")
        val needsPassword = Regex("""<input\b[^>]*\bname\s*=\s*["']pass["']""", ic).containsMatchIn(body)
        return OneFichierForm(action, fields, needsPassword)
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", ic).find(attrs)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } } }

    /**
     * Captcha-Markierungen im Download-Formular (bzw. auf der Seite, wenn kein
     * Formular gefunden wurde): Turnstile, reCAPTCHA, hCaptcha, Cloudflare-
     * Herausforderung. Im Normalablauf verlangt 1fichier kein Captcha; es
     * erscheint nur bei auffaelligen Adressen und ist dann nur im Browser
     * loesbar.
     */
    fun hasCaptcha(html: String): Boolean {
        // Alle POST-Formulare ausser dem Login (Feld "mail"); ohne Formular die ganze Seite
        val forms = Regex("""<form\b[^>]*method\s*=\s*["']?post[^>]*>[\s\S]*?</form>""", ic).findAll(html)
            .map { it.value }
            .filterNot { Regex("""name\s*=\s*["']mail["']""", ic).containsMatchIn(it) }
            .toList()
        val scope = if (forms.isEmpty()) html else forms.joinToString("\n")
        return Regex(
            """data-sitekey\s*=|class=["'][^"']*\b(?:g-recaptcha|h-captcha|cf-turnstile)\b|""" +
                """name=["'](?:g-recaptcha-response|h-captcha-response|cf-turnstile-response)["']|""" +
                """challenges\.cloudflare\.com/turnstile|cf-challenge|id=["']challenge-form["']""",
            ic
        ).containsMatchIn(scope)
    }

    /**
     * Direktlink aus der Antwort auf das Formular:
     * `<a href="https://a-3.1fichier.com/c123456">Click here to download`
     * (aktuell), Knopfklasse `ok btn-general btn-orange`, "Start your
     * download" oder `window.location = '…'`; zuletzt jede Fileserver-
     * Adresse im HTML. Jeder Kandidat muss [isFileServerUrl] bestehen:
     * eine Sperr- oder Hinweisseite enthaelt ebenfalls `href`s auf
     * Subdomains (`static.1fichier.com/css/…`, `www.1fichier.com/register.pl`),
     * die sonst als Datei geladen wuerden.
     */
    fun directLink(html: String): String? {
        val patterns = listOf(
            """<a\s+href=["']([^"']+)["'][^>]*>\s*(?:<[^>]+>\s*)*Click here to download""",
            """<a\s+href=["']([^"']+)["'][^>]*class=["'][^"']*btn-orange[^"']*["']""",
            """<a\s+href=["']([^"']+)["'][^>]*>\s*(?:<[^>]+>\s*)*Start your download""",
            """window\.location(?:\.href)?\s*=\s*["'](https?://[^"']+)["']""",
            """href=["'](https?://[^"']+)["']"""
        )
        for (p in patterns) {
            Regex(p, ic).findAll(html)
                .map { unescape(it.groupValues[1]).trim() }
                .firstOrNull { isFileServerUrl(it) }
                ?.let { return it }
        }
        return null
    }

    /** Subdomains der Hoster-Domain, auf denen nur Seiten und Ressourcen liegen. */
    private val pageSubdomains = setOf("www", "static", "img", "api", "cdn", "help", "console")

    /**
     * Fileserver-Adresse: `https://a-<n>.1fichier.com/<token>` - Subdomain
     * der Hoster-Domain (nicht www/static/…), genau ein Pfadsegment aus
     * mindestens vier Buchstaben/Ziffern ohne Dateiendung, danach hoechstens
     * Query oder Fragment. Seiten der Hauptdomain (`1fichier.com/?id`),
     * Ressourcen (`static.1fichier.com/css/main.css`) und Seitenlinks
     * (`www.1fichier.com/register.pl`) zaehlen nie.
     */
    fun isFileServerUrl(url: String): Boolean {
        val m = Regex(
            """^https?://([A-Za-z0-9_\-]+)\.(?:1fichier|desfichiers)\.com/[A-Za-z0-9]{4,}(?:[?#][^/]*)?$""",
            ic
        ).find(url) ?: return false
        return m.groupValues[1].lowercase() !in pageSubdomains
    }

    /**
     * Hinweistexte der Website (sichtbarer Text). Reihenfolge: erst
     * endgueltige Gruende, dann Muster mit Zeitangabe, dann pauschale
     * Sperren. Wartezeiten sind in Minuten angegeben; +1 s Reserve.
     *
     * [downloadOffered] = die Seite bietet das Download-Formular an: dann
     * zaehlen die Sperren ohne Zeitangabe ("only one file at a time") nicht -
     * derselbe Satz steht als Hinweis auf jeder Free-Dateiseite. Ob wirklich
     * gesperrt ist, sagt die Antwort auf das Formular (ohne Formular), und
     * dort gelten die Muster wieder.
     */
    fun classify(text: String, downloadOffered: Boolean = false): OneFichierBlock? {
        val t = text.replace("&#039;", "'").replace("&apos;", "'").replace("&nbsp;", " ")

        // --- endgueltig: Datei weg, nur mit Konto, geschuetzt ---
        if (Regex("""File not found|The requested file (?:has been deleted|do(?:es)? not exist)""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Permanent("Datei ist offline")
        }
        if (Regex(
                """not possible to free unregistered users|is not possible to unregistered users|""" +
                    """need a subscription|only for (?:registered|premium) users""",
                ic
            ).containsMatchIn(t)
        ) {
            return OneFichierBlock.Permanent("1fichier: Datei nur mit Konto ladbar (Besitzer sperrt Free-Download)")
        }
        if (Regex("""Access to this file is protected|This file is protected""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Permanent("1fichier: Zugriff auf die Datei ist vom Besitzer eingeschränkt")
        }
        if (Regex("""Bad password|Mauvais mot de passe""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Permanent("1fichier: Passwort falsch")
        }

        // --- Wartezeit mit Zahl (Minuten) ---
        Regex(
            """(?:you must wait (?:at least|up to)|You must wait|Vous devez attendre(?: encore)?)\s*(\d+)\s*(min|sec|hour|heure)""",
            ic
        ).find(t)?.let {
            val n = it.groupValues[1].toInt()
            val secs = when (it.groupValues[2].lowercase().first()) {
                'h' -> n * 3600
                's' -> n
                else -> n * 60
            }
            return OneFichierBlock.Wait(secs + 1, "1fichier: nächster Free-Download in ${waitText(secs)}")
        }
        if (Regex("""IP Locked|Will be unlocked within 1\s*h""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(3600 + 1, "1fichier: IP-Adresse gesperrt – Freigabe in einer Stunde")
        }

        // --- nur ein Free-Download je IP (ohne Zahl: 5 Minuten) - nicht auf der Dateiseite mit Formular ---
        if (!downloadOffered && Regex(
                """You already downloading (?:some|a) file|You can download only one file at a time|""" +
                    """You must wait for another download|Please wait a few seconds before downloading new ones|""" +
                    """Without (?:premium status|Premium|subscription), you (?:can|must)[^<]{0,60}(?:one file at a time|wait between downloads)|""" +
                    """Téléchargements en cours|veuillez patienter avant de télécharger un autre fichier|""" +
                    """Votre adresse IP ouvre trop de connexions vers le serveur""",
                ic
            ).containsMatchIn(t)
        ) {
            return OneFichierBlock.Wait(5 * 60 + 1, "1fichier: im Free-Modus nur ein Download gleichzeitig je Adresse")
        }

        // --- weitere voruebergehende Zustaende ---
        if (Regex("""Free download is temporarily limited due to high demand""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(60 + 1, "1fichier: derzeit keine freien Download-Plätze")
        }
        if (Regex("""Your requests are too fast""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(30 + 1, "1fichier: Anfragen zu schnell – kurze Pause")
        }
        if (Regex("""Software error|Can't connect DB|Connexion à la base de données impossible""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(5 * 60 + 1, "1fichier: Serverfehler – in fünf Minuten erneut")
        }
        if (Regex("""Our services are in maintenance""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(20 * 60 + 1, "1fichier: Wartung – in 20 Minuten erneut")
        }
        if (Regex("""The free offer is intended to""", ic).containsMatchIn(t) &&
            Regex("""You already downloaded for free more than|It is not designed for intensive or continuous use""", ic)
                .containsMatchIn(t)
        ) {
            return OneFichierBlock.Wait(3600 + 1, "1fichier: Free-Nutzung vorübergehend gesperrt (zu viele Downloads)")
        }
        if (Regex(
                """Accès restreint|professional infrastructure detected|""" +
                    """identified as belonging to a server, proxy, VPN""",
                ic
            ).containsMatchIn(t)
        ) {
            return OneFichierBlock.Transient(
                "1fichier: Free-Download von Server-/VPN-Adressen gesperrt – VPN ausschalten oder Konto hinterlegen"
            )
        }
        return null
    }

    private fun waitText(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} Stunde${if (seconds / 3600 == 1) "" else "n"}"
        seconds % 60 == 0 -> "${seconds / 60} Minute${if (seconds / 60 == 1) "" else "n"}"
        else -> "$seconds Sekunden"
    }

    private fun unescape(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
}
