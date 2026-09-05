package com.jdandroid.hoster

import com.jdandroid.core.Texts

/**
 * Classification of a notice text of the 1fichier website in free mode (file
 * page or response to the download form).
 */
internal sealed class OneFichierBlock {
    /** Wait in seconds (including margin) with a translated message. */
    data class Wait(val seconds: Int, val text: String) : OneFichierBlock()

    /** Not downloadable without an account; retrying is pointless. */
    data class Permanent(val text: String) : OneFichierBlock()

    /** Temporary, but without a fixed wait (engine retry). */
    data class Transient(val text: String) : OneFichierBlock()
}

/** The download form of the file page (POST to the same address). */
internal data class OneFichierForm(
    val action: String?,
    val fields: Map<String, String>,
    /** File is password protected (field "pass"). */
    val needsPassword: Boolean
)

/**
 * Pure parsing of the 1fichier file page in free mode (no network, no
 * Android), so every rule stays testable against real page snippets.
 *
 * Layout (as of 09/2026, English via `LG=en`): name and size in a table
 * (`Filename :</td><td>…`, `Size :</td><td>1.2 GB`), the form
 * `<form action="https://1fichier.com/?<id>" method="post">` with the hidden
 * random field `adz`, the countdown as `var count = 30;`. After the POST the
 * direct link appears as `<a href="https://a-3.1fichier.com/…">Click here to
 * download`; blocks are visible text ("You must wait 5 minutes", "You already
 * downloading a file"). A 404 comes as
 * `<div class="notice alc">The requested file does not exist`.
 */
internal object OneFichierFreePage {

    private val ic = RegexOption.IGNORE_CASE

    /** Visible text without scripts, styles, comments and tags. */
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

    /** Size in bytes (1024-based), -1 if not stated. */
    fun fileSize(html: String): Long {
        val m = Regex("""(?:Size|Taille)\s*:?\s*</td>\s*<td[^>]*>\s*([\d.,]+)\s*([KMGT]?[Bo])\b""", ic).find(html)
            ?: return -1
        return toBytes(m.groupValues[1], m.groupValues[2])
    }

    /** "1.5 GB", "1,5 Go", "700 MB" → bytes; the French unit "o" (octet) counts like "B". */
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
     * Countdown before the download button in seconds (`var count = 30;`,
     * variants `Free download in ⏳ 30`, `var ct = 30`), 0 = none.
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
     * Download form: the first POST form whose action contains `/?<id>` (or
     * `id="f1"`); without an id the first POST form. All hidden fields are
     * taken over, `save` (store in account) is removed. null = no form
     * (block, notice page).
     */
    fun downloadForm(html: String, id: String? = null): OneFichierForm? {
        val forms = Regex("""<form\b([^>]*)>([\s\S]*?)</form>""", ic).findAll(html).toList()
        val posts = forms.filter { Regex("""method\s*=\s*["']?post""", ic).containsMatchIn(it.groupValues[1]) }
        val chosen = posts.firstOrNull { m ->
            val attrs = m.groupValues[1]
            (id != null && Regex("""action\s*=\s*["'][^"']*\?$id(?:[&"']|$)""", ic).containsMatchIn(attrs)) ||
                Regex("""\bid\s*=\s*["']f1["']""", ic).containsMatchIn(attrs)
        } ?: posts.firstOrNull { m ->
            // Exclude login and search forms: the download form has no "mail" field
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
     * Captcha markers in the download form (or on the page if no form was
     * found): Turnstile, reCAPTCHA, hCaptcha, Cloudflare challenge. 1fichier
     * normally requires no captcha; it appears only for suspicious addresses
     * and is then only solvable in the browser.
     */
    fun hasCaptcha(html: String): Boolean {
        // All POST forms except the login (field "mail"); without a form the whole page
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
     * Direct link from the form response:
     * `<a href="https://a-3.1fichier.com/c123456">Click here to download`
     * (current), button class `ok btn-general btn-orange`, "Start your
     * download" or `window.location = '…'`; finally any file server address
     * in the HTML. Every candidate must pass [isFileServerUrl]: a block or
     * notice page also contains hrefs to subdomains
     * (`static.1fichier.com/css/…`, `www.1fichier.com/register.pl`) that
     * would otherwise be downloaded as the file.
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

    /** Subdomains of the hoster domain that serve only pages and resources. */
    private val pageSubdomains = setOf("www", "static", "img", "api", "cdn", "help", "console")

    /**
     * File server address `https://a-<n>.1fichier.com/<token>`: a subdomain
     * of the hoster domain (not www/static/…), exactly one path segment of at
     * least four letters/digits without an extension, then at most query or
     * fragment. Main domain pages (`1fichier.com/?id`), resources
     * (`static.1fichier.com/css/main.css`) and page links
     * (`www.1fichier.com/register.pl`) never count.
     */
    fun isFileServerUrl(url: String): Boolean {
        val m = Regex(
            """^https?://([A-Za-z0-9_\-]+)\.(?:1fichier|desfichiers)\.com/[A-Za-z0-9]{4,}(?:[?#][^/]*)?$""",
            ic
        ).find(url) ?: return false
        return m.groupValues[1].lowercase() !in pageSubdomains
    }

    /**
     * Website notice texts (visible text). Order: permanent reasons first,
     * then patterns with a time, then generic blocks. Waits are stated in
     * minutes; +1 s margin.
     *
     * [downloadOffered] = the page offers the download form: then the blocks
     * without a time ("only one file at a time") do not count, since the same
     * sentence is a notice on every free file page. Whether it is really
     * blocked is shown by the form response (without a form), where the
     * patterns apply again.
     */
    fun classify(text: String, downloadOffered: Boolean = false): OneFichierBlock? {
        val t = text.replace("&#039;", "'").replace("&apos;", "'").replace("&nbsp;", " ")

        // permanent: file gone, account only, protected
        if (Regex("""File not found|The requested file (?:has been deleted|do(?:es)? not exist)""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Permanent(Texts.t("hoster_file_offline"))
        }
        if (Regex(
                """not possible to free unregistered users|is not possible to unregistered users|""" +
                    """need a subscription|only for (?:registered|premium) users""",
                ic
            ).containsMatchIn(t)
        ) {
            return OneFichierBlock.Permanent(Texts.t("hoster_onefichier_account_required"))
        }
        if (Regex("""Access to this file is protected|This file is protected""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Permanent(Texts.t("hoster_onefichier_access_restricted"))
        }
        if (Regex("""Bad password|Mauvais mot de passe""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Permanent(Texts.t("hoster_onefichier_bad_password"))
        }

        // wait with a number (minutes)
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
            return OneFichierBlock.Wait(secs + 1, Texts.t("hoster_onefichier_next_free_in", HosterDurations.text(secs)))
        }
        if (Regex("""IP Locked|Will be unlocked within 1\s*h""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(3600 + 1, Texts.t("hoster_onefichier_ip_locked"))
        }

        // one free download per IP (no number: 5 minutes); not on the file page with a form
        if (!downloadOffered && Regex(
                """You already downloading (?:some|a) file|You can download only one file at a time|""" +
                    """You must wait for another download|Please wait a few seconds before downloading new ones|""" +
                    """Without (?:premium status|Premium|subscription), you (?:can|must)[^<]{0,60}(?:one file at a time|wait between downloads)|""" +
                    """Téléchargements en cours|veuillez patienter avant de télécharger un autre fichier|""" +
                    """Votre adresse IP ouvre trop de connexions vers le serveur""",
                ic
            ).containsMatchIn(t)
        ) {
            return OneFichierBlock.Wait(5 * 60 + 1, Texts.t("hoster_onefichier_one_at_a_time"))
        }

        // other temporary states
        if (Regex("""Free download is temporarily limited due to high demand""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(60 + 1, Texts.t("hoster_onefichier_no_free_slots"))
        }
        if (Regex("""Your requests are too fast""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(30 + 1, Texts.t("hoster_onefichier_too_fast"))
        }
        if (Regex("""Software error|Can't connect DB|Connexion à la base de données impossible""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(5 * 60 + 1, Texts.t("hoster_onefichier_server_error"))
        }
        if (Regex("""Our services are in maintenance""", ic).containsMatchIn(t)) {
            return OneFichierBlock.Wait(20 * 60 + 1, Texts.t("hoster_onefichier_maintenance"))
        }
        if (Regex("""The free offer is intended to""", ic).containsMatchIn(t) &&
            Regex("""You already downloaded for free more than|It is not designed for intensive or continuous use""", ic)
                .containsMatchIn(t)
        ) {
            return OneFichierBlock.Wait(3600 + 1, Texts.t("hoster_onefichier_free_overuse"))
        }
        if (Regex(
                """Accès restreint|professional infrastructure detected|""" +
                    """identified as belonging to a server, proxy, VPN""",
                ic
            ).containsMatchIn(t)
        ) {
            return OneFichierBlock.Transient(Texts.t("hoster_onefichier_vpn_blocked"))
        }
        return null
    }

    private fun unescape(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
}
