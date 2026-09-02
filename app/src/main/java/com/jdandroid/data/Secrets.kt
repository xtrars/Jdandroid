package com.jdandroid.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Verschluesselt Zugangsdaten mit einem Schluessel aus dem Android-Keystore.
 * Der Schluessel verlaesst die Hardware nie; in der Datenbank steht nur noch
 * Chiffrat statt Klartext-Passwoertern.
 *
 * Werte ohne Praefix stammen aus aelteren Installationen und werden weiterhin
 * im Klartext gelesen, damit ein Update keine Konten unbrauchbar macht.
 */
object Secrets {

    private const val KEY_ALIAS = "jdandroid_credentials"
    private const val PREFIX = "enc1:"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** Keystore nicht nutzbar: Zugangsdaten werden dann NICHT gespeichert. */
    class SecretsException(message: String, cause: Throwable?) : Exception(message, cause)

    /**
     * Verschluesselt [plain]. Schlaegt der Keystore fehl, wird bewusst eine
     * Exception geworfen statt still im Klartext zu speichern - der Aufrufer
     * zeigt dem Nutzer eine Meldung.
     */
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
        } catch (e: Exception) {
            throw SecretsException(
                "Zugangsdaten konnten nicht verschlüsselt werden " +
                    "(Android-Keystore nicht verfügbar: ${e.message ?: e.javaClass.simpleName}). " +
                    "Bitte Gerät entsperren und erneut versuchen.",
                e
            )
        }
    }

    fun decrypt(value: String?): String? {
        if (value.isNullOrEmpty()) return value
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val raw = Base64.getDecoder().decode(value.removePrefix(PREFIX))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH)
            )
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
    }
}

/** Entschluesselte Sicht auf die gespeicherten Zugangsdaten. */
val Account.plainPassword: String? get() = Secrets.decrypt(password)
val Account.plainApiKey: String? get() = Secrets.decrypt(apiKey)
val Account.plainCookies: String? get() = Secrets.decrypt(cookies)
