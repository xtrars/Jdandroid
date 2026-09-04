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

    /** Nur lesen: beim Entschluesseln darf nie ein neuer Schluessel entstehen. */
    @Synchronized
    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        existingKey()?.let { return it }
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

    /** Ist der Wert bereits verschluesselt gespeichert? */
    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /**
     * Entschluesselt [value]. Schlaegt das bei einem verschluesselten Wert fehl
     * (Keystore-Eintrag nach Systemupdate nicht mehr lesbar), wird eine
     * [SecretsException] geworfen - statt still null, was frueher als
     * "kein Passwort hinterlegt" erschien.
     */
    fun decrypt(value: String?): String? {
        if (value.isNullOrEmpty()) return value
        if (!value.startsWith(PREFIX)) return value
        return try {
            val raw = Base64.getDecoder().decode(value.removePrefix(PREFIX))
            val key = existingKey() ?: throw IllegalStateException("Schlüssel fehlt im Keystore")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretsException(
                "Zugangsdaten nicht lesbar (Android-Keystore: ${e.message ?: e.javaClass.simpleName}). " +
                    "Bitte das Konto löschen und neu anlegen.",
                e
            )
        }
    }
}

/** Entschluesselte Sicht auf die gespeicherten Zugangsdaten. */
val Account.plainPassword: String? get() = Secrets.decrypt(password)
val Account.plainApiKey: String? get() = Secrets.decrypt(apiKey)
val Account.plainCookies: String? get() = Secrets.decrypt(cookies)
