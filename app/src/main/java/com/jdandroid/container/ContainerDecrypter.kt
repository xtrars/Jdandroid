package com.jdandroid.container

import com.jdandroid.hoster.Http
import okhttp3.Request
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Entschluesselt Link-Container: DLC (DownLoad Container) und die
 * Click'n'Load-2-Nutzlast. Beide liefern am Ende eine Liste roher URLs,
 * die dann durch den normalen LinkParser laufen.
 */
object ContainerDecrypter {

    // Statischer Schluessel/IV, mit dem der vom JDownloader-Dienst gelieferte
    // Container-Schluessel (rc) entschluesselt wird. Öffentlich bekannt aus
    // diversen Open-Source-DLC-Implementierungen (pyLoad u.a.).
    private val DLC_KEY = "cb99b5cbc24db398".toByteArray(Charsets.US_ASCII)
    // ACHTUNG: IV ist NICHT die umgestellte KEY-Zeichenfolge. Ein falscher IV
    // liefert bei einem einzelnen CBC-Block trotzdem ASCII-aehnliche Bytes
    // (er geht nur per XOR ein) und taeuscht damit einen korrekten Schluessel vor.
    private val DLC_IV = "9bc24cb995cb8db3".toByteArray(Charsets.US_ASCII)

    private const val DLC_SERVICE =
        "https://service.jdownloader.org/dlcrypt/service.php?srcType=dlc&destType=pylo&data="

    class ContainerException(message: String) : Exception(message)

    /** Ein Paket aus dem DLC mit (dekodiertem) Namen und seinen Links. */
    data class DlcPackage(val name: String?, val urls: List<String>)

    /** Holt den Container-Schluessel (rc) vom JDownloader-Dienst. */
    private fun fetchRc(dlcKey: String): String {
        val response = try {
            Http.client.newCall(
                Request.Builder().url(DLC_SERVICE + dlcKey)
                    .header("User-Agent", Http.USER_AGENT).build()
            ).execute().use { it.peekBody(Http.MAX_TEXT_BYTES).string() }
        } catch (e: Exception) {
            throw ContainerException("DLC-Dienst nicht erreichbar: ${e.message}")
        }
        return Regex("<rc>(.+?)</rc>", RegexOption.DOT_MATCHES_ALL).find(response)
            ?.groupValues?.get(1)?.trim()
            ?: throw ContainerException(
                "DLC konnte nicht entschlüsselt werden – der JDownloader-DLC-Dienst " +
                    "hat keinen Schlüssel geliefert (Dienst evtl. abgeschaltet)."
            )
    }

    /**
     * Leitet aus der Dienst-Antwort (rc, base64) den realen Container-Schluessel
     * ab. Er dient anschliessend zugleich als Schluessel und IV fuer die Daten.
     */
    internal fun deriveKey(rcBase64: String): ByteArray {
        val rc = base64(rcBase64)
        if (rc.size < 16) throw ContainerException("DLC-Dienst lieferte ungültige Antwort")
        // exakt ein Block: eine laengere Antwort wuerde bei NoPadding werfen
        return aesCbcDecrypt(rc.copyOf(16), DLC_KEY, DLC_IV).copyOf(16)
    }

    /**
     * Entschluesselt den Inhalt einer .dlc-Datei zu Paketen mit URLs.
     * Benoetigt den JDownloader-DLC-Dienst.
     */
    fun decryptDlcPackages(dlcContent: String): List<DlcPackage> {
        // Kommt der Container per Formular (Click'n'Load /flash/addcrypted),
        // hat der Browser "+" bereits zu Leerzeichen dekodiert - zurueckwandeln.
        val data = dlcContent.replace(' ', '+').filterNot { it.isWhitespace() }
        if (data.length < 88) throw ContainerException("DLC-Datei zu kurz oder ungültig")

        val dlcKey = data.substring(data.length - 88)
        val dlcData = base64(data.substring(0, data.length - 88))
        if (dlcData.size % 16 != 0) throw ContainerException("DLC-Datei beschädigt")

        val realKey = deriveKey(fetchRc(dlcKey))
        val xml = decodeXml(aesCbcDecrypt(dlcData, realKey, realKey))
        val packages = parsePackages(xml)
        if (packages.all { it.urls.isEmpty() }) {
            throw ContainerException("DLC enthielt keine lesbaren Links")
        }
        return packages.filter { it.urls.isNotEmpty() }
    }

    /** Alle URLs eines DLC ohne Paketstruktur. */
    fun decryptDlc(dlcContent: String): List<String> =
        decryptDlcPackages(dlcContent).flatMap { it.urls }

    /** AES/NoPadding hinterlaesst Auffuellbytes; nur gueltige base64-Zeichen behalten. */
    internal fun decodeXml(plain: ByteArray): String =
        String(base64(onlyBase64(String(plain, Charsets.US_ASCII))), Charsets.UTF_8)

    /**
     * DLC-XML: <content><package name="BASE64"><file><url>BASE64</url>...</file></package>
     * Paketname und jede URL sind jeweils nochmals base64-kodiert.
     */
    internal fun parsePackages(xml: String): List<DlcPackage> {
        val packageRegex = Regex(
            """<package\b([^>]*)>(.*?)</package>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val urlRegex = Regex("<url>(.+?)</url>", RegexOption.DOT_MATCHES_ALL)
        fun urlsIn(block: String): List<String> = urlRegex.findAll(block)
            .mapNotNull { m -> runCatching { String(base64(m.groupValues[1].trim()), Charsets.UTF_8) }.getOrNull() }
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .toList()

        val packages = packageRegex.findAll(xml).map { m ->
            val nameB64 = Regex("""name=["']([^"']*)["']""").find(m.groupValues[1])?.groupValues?.get(1)
            val name = nameB64?.let { n ->
                runCatching { String(base64(n), Charsets.UTF_8).trim() }.getOrNull()
            }?.ifBlank { null }
            DlcPackage(name, urlsIn(m.groupValues[2]))
        }.toList()

        // DLC ohne <package>-Struktur: alle URLs als ein Paket
        return packages.ifEmpty { listOf(DlcPackage(null, urlsIn(xml))) }
    }

    /**
     * Click'n'Load 2: entschluesselt die vom Browser gesendete Nutzlast.
     * [jk] ist eine JS-Funktion mit dem Hex-Schluessel, [crypted] die
     * Base64-AES-CBC-verschluesselten Links.
     */
    fun decryptClickNLoad(crypted: String, jk: String): List<String> {
        val hexKey = Regex("[\"']([0-9a-fA-F]{16,})[\"']").find(jk)?.groupValues?.get(1)
            ?: throw ContainerException("Click'n'Load: kein Schlüssel gefunden")
        // Das Protokoll ist AES-128: genau 16 Byte, die zugleich als IV dienen.
        // Ein laengerer Wert ergaebe einen unbrauchbaren IV und eine kryptische
        // Exception statt einer klaren Meldung.
        if (hexKey.length != 32) {
            throw ContainerException("Click'n'Load: Schlüssel hat ungültige Länge (${hexKey.length / 2} Byte)")
        }
        val key = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Rohes "+" im Formular wird vom Browser als Leerzeichen dekodiert
        val decoded = base64(crypted.replace(' ', '+').trim())
        if (decoded.isEmpty() || decoded.size % 16 != 0) {
            throw ContainerException("Click'n'Load: Daten beschädigt (Länge ${decoded.size})")
        }
        val plain = aesCbcDecrypt(decoded, key, key)
        // Nullbytes (Blockpadding) entfernen, sonst haengen sie am letzten Link
        return String(plain, Charsets.UTF_8).replace("\u0000", "")
            .split('\n', '\r')
            .map { it.trim().trimEnd('\u0000') }
            .filter { it.startsWith("http", ignoreCase = true) }
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(data)
    }

    /** Behaelt nur gueltige base64-Zeichen (entfernt Nullbytes, Zeilenumbrueche, Muell). */
    private fun onlyBase64(s: String): String = s.filter {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
    }

    private fun base64(s: String): ByteArray {
        val cleaned = onlyBase64(s).substringBefore('=')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        // java.util.Base64 (API 26+) statt android.util.Base64: identisches
        // Verhalten, aber ohne Android-Framework testbar.
        return java.util.Base64.getDecoder().decode(padded)
    }
}
