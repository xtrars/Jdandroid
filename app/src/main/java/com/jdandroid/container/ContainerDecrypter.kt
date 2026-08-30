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
    private val DLC_IV = "9bc24db398cb99b5".toByteArray(Charsets.US_ASCII)

    private const val DLC_SERVICE =
        "http://service.jdownloader.org/dlcrypt/service.php?srcType=dlc&destType=pylo&data="

    class ContainerException(message: String) : Exception(message)

    /**
     * Entschluesselt den Inhalt einer .dlc-Datei zu einer Liste von URLs.
     * Benoetigt den (historischen) JDownloader-DLC-Dienst.
     */
    fun decryptDlc(dlcContent: String): List<String> {
        val data = dlcContent.trim().filterNot { it.isWhitespace() }
        if (data.length < 88) throw ContainerException("DLC-Datei zu kurz oder ungültig")

        val dlcKey = data.substring(data.length - 88)
        val dlcData = base64(data.substring(0, data.length - 88))

        val response = try {
            Http.client.newCall(
                Request.Builder().url(DLC_SERVICE + dlcKey)
                    .header("User-Agent", Http.USER_AGENT).build()
            ).execute().use { it.body?.string() ?: "" }
        } catch (e: Exception) {
            throw ContainerException("DLC-Dienst nicht erreichbar: ${e.message}")
        }

        val rc = Regex("<rc>(.+?)</rc>").find(response)?.groupValues?.get(1)
            ?: throw ContainerException(
                "DLC konnte nicht entschlüsselt werden – der JDownloader-DLC-Dienst " +
                    "hat keinen Schlüssel geliefert (Dienst evtl. abgeschaltet)."
            )
        val rcDecoded = base64(rc)
        if (rcDecoded.size < 16) throw ContainerException("DLC-Dienst lieferte ungültige Antwort")

        // rc -> realer Schluessel (dient zugleich als IV)
        val realKey = aesCbcDecrypt(rcDecoded, DLC_KEY, DLC_IV).copyOf(16)
        val xmlBase64 = aesCbcDecrypt(dlcData, realKey, realKey)
        // AES/NoPadding hinterlaesst Auffuell-Nullbytes; nur gueltige base64-Zeichen behalten
        val cleaned = onlyBase64(String(xmlBase64, Charsets.US_ASCII))
        val xml = String(base64(cleaned), Charsets.UTF_8)

        val urls = Regex("<url>(.+?)</url>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .mapNotNull { match ->
                runCatching { String(base64(match.groupValues[1].trim()), Charsets.UTF_8) }
                    .getOrNull()
            }
            .filter { it.startsWith("http", ignoreCase = true) }
            .toList()

        if (urls.isEmpty()) throw ContainerException("DLC enthielt keine lesbaren Links")
        return urls
    }

    /**
     * Click'n'Load 2: entschluesselt die vom Browser gesendete Nutzlast.
     * [jk] ist eine JS-Funktion mit dem Hex-Schluessel, [crypted] die
     * Base64-AES-CBC-verschluesselten Links.
     */
    fun decryptClickNLoad(crypted: String, jk: String): List<String> {
        val hexKey = Regex("[\"']([0-9a-fA-F]{16,})[\"']").find(jk)?.groupValues?.get(1)
            ?: throw ContainerException("Click'n'Load: kein Schlüssel gefunden")
        val key = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = base64(crypted.trim())
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
