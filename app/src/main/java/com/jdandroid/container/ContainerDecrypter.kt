package com.jdandroid.container

import com.jdandroid.hoster.Http
import okhttp3.Request
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts link containers: DLC (DownLoad Container) and the Click'n'Load 2
 * payload. Both end up as a list of raw URLs that then go through the normal
 * LinkParser.
 */
object ContainerDecrypter {

    // Static key/IV used to decrypt the container key (rc) returned by the
    // JDownloader service; publicly known from open-source DLC
    // implementations (pyLoad and others).
    private val DLC_KEY = "cb99b5cbc24db398".toByteArray(Charsets.US_ASCII)
    // The IV is NOT the rearranged KEY string. A wrong IV still yields
    // ASCII-like bytes for a single CBC block (it only enters via XOR) and
    // thus fakes a correct key.
    private val DLC_IV = "9bc24cb995cb8db3".toByteArray(Charsets.US_ASCII)

    private const val DLC_SERVICE =
        "https://service.jdownloader.org/dlcrypt/service.php?srcType=dlc&destType=pylo&data="

    class ContainerException(message: String) : Exception(message)

    /** A package from the DLC with its (decoded) name and links. */
    data class DlcPackage(val name: String?, val urls: List<String>)

    /** Fetches the container key (rc) from the JDownloader service. */
    private fun fetchRc(dlcKey: String): String {
        val response = try {
            Http.client.newCall(
                Request.Builder().url(DLC_SERVICE + dlcKey)
                    .header("User-Agent", Http.USER_AGENT).build()
            ).execute().use { it.peekBody(Http.MAX_TEXT_BYTES).string() }
        } catch (e: Exception) {
            throw ContainerException(
                ContainerTexts.t("service_dlc_service_unreachable", e.message ?: e.javaClass.simpleName)
            )
        }
        return Regex("<rc>(.+?)</rc>", RegexOption.DOT_MATCHES_ALL).find(response)
            ?.groupValues?.get(1)?.trim()
            ?: throw ContainerException(ContainerTexts.t("service_dlc_service_no_key"))
    }

    /**
     * Derives the real container key from the service reply (rc, base64). It
     * then serves as both key and IV for the data.
     */
    internal fun deriveKey(rcBase64: String): ByteArray {
        val rc = base64(rcBase64)
        if (rc.size < 16) throw ContainerException(ContainerTexts.t("service_dlc_service_invalid_reply"))
        // exactly one block: a longer reply would throw with NoPadding
        return aesCbcDecrypt(rc.copyOf(16), DLC_KEY, DLC_IV).copyOf(16)
    }

    /**
     * Decrypts the content of a .dlc file into packages with URLs. Requires
     * the JDownloader DLC service.
     */
    fun decryptDlcPackages(dlcContent: String): List<DlcPackage> {
        // When the container arrives via form (Click'n'Load /flash/addcrypted)
        // the browser has already decoded "+" to spaces; convert back.
        val data = dlcContent.replace(' ', '+').filterNot { it.isWhitespace() }
        if (data.length < 88) throw ContainerException(ContainerTexts.t("service_dlc_too_short"))

        val dlcKey = data.substring(data.length - 88)
        val dlcData = base64(data.substring(0, data.length - 88))
        if (dlcData.size % 16 != 0) throw ContainerException(ContainerTexts.t("service_dlc_corrupt"))

        val realKey = deriveKey(fetchRc(dlcKey))
        val xml = decodeXml(aesCbcDecrypt(dlcData, realKey, realKey))
        val packages = parsePackages(xml)
        if (packages.all { it.urls.isEmpty() }) {
            throw ContainerException(ContainerTexts.t("service_dlc_no_links"))
        }
        return packages.filter { it.urls.isNotEmpty() }
    }

    /** All URLs of a DLC without package structure. */
    fun decryptDlc(dlcContent: String): List<String> =
        decryptDlcPackages(dlcContent).flatMap { it.urls }

    /** AES/NoPadding leaves padding bytes; keep only valid base64 characters. */
    internal fun decodeXml(plain: ByteArray): String =
        String(base64(onlyBase64(String(plain, Charsets.US_ASCII))), Charsets.UTF_8)

    /**
     * DLC XML: <content><package name="BASE64"><file><url>BASE64</url>...</file></package>
     * The package name and every URL are base64-encoded once more.
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

        // DLC without <package> structure: all URLs as one package
        return packages.ifEmpty { listOf(DlcPackage(null, urlsIn(xml))) }
    }

    /**
     * Click'n'Load 2: decrypts the payload sent by the browser. [jk] is a JS
     * function containing the hex key, [crypted] the base64 AES-CBC encrypted
     * links.
     */
    fun decryptClickNLoad(crypted: String, jk: String): List<String> {
        // Key as a hex literal; some pages assemble it from several parts
        // ('abcd' + 'ef01' ...), then all literals are concatenated.
        val literals = Regex("[\"']([0-9a-fA-F]{2,})[\"']").findAll(jk).map { it.groupValues[1] }.toList()
        val hexKey = literals.firstOrNull { it.length == 32 }
            ?: literals.joinToString("").takeIf { it.length == 32 }
            ?: literals.firstOrNull { it.length >= 16 }
            ?: throw ContainerException(ContainerTexts.t("service_cnl_key_missing"))
        // The protocol is AES-128: exactly 16 bytes, also used as the IV. A
        // longer value would give an unusable IV and a cryptic exception
        // instead of a clear message.
        if (hexKey.length != 32) {
            throw ContainerException(ContainerTexts.t("service_cnl_key_invalid_length", hexKey.length / 2))
        }
        val key = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // A raw "+" in the form is decoded as a space by the browser
        val decoded = base64(crypted.replace(' ', '+').trim())
        if (decoded.isEmpty() || decoded.size % 16 != 0) {
            throw ContainerException(ContainerTexts.t("service_cnl_data_corrupt", decoded.size))
        }
        val plain = aesCbcDecrypt(decoded, key, key)
        // Strip null bytes (block padding), otherwise they stick to the last link
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

    /** Keeps only valid base64 characters (drops null bytes, line breaks, junk). */
    private fun onlyBase64(s: String): String = s.filter {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
    }

    private fun base64(s: String): ByteArray {
        val cleaned = onlyBase64(s).substringBefore('=')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        // java.util.Base64 (API 26+) instead of android.util.Base64: same
        // behaviour, but testable without the Android framework.
        return java.util.Base64.getDecoder().decode(padded)
    }
}
