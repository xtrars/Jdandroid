package com.jdandroid.data

import com.jdandroid.core.Texts
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts credentials with an AES-GCM key from the Android Keystore; the
 * key never leaves the hardware. Values without the prefix come from older
 * installations and are still read as plain text so an update does not
 * break accounts.
 */
object Secrets {

    private const val KEY_ALIAS = "jdandroid_credentials"
    private const val PREFIX = "enc1:"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /** Read-only lookup: decryption must never create a new key. */
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

    /** Keystore unusable: credentials are not stored at all. */
    class SecretsException(message: String, cause: Throwable?) : Exception(message, cause)

    /** Encrypts [plain]; a Keystore failure throws instead of storing plain text. */
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
        } catch (e: Exception) {
            throw SecretsException(Texts.t("engine_secrets_encrypt_failed", e.message ?: e.javaClass.simpleName), e)
        }
    }

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /**
     * Decrypts [value]. A failure on an encrypted value (Keystore entry
     * unreadable after a system update) throws [SecretsException] rather than
     * returning null, which would look like "no password stored".
     */
    fun decrypt(value: String?): String? {
        if (value.isNullOrEmpty()) return value
        if (!value.startsWith(PREFIX)) return value
        return try {
            val raw = Base64.getDecoder().decode(value.removePrefix(PREFIX))
            val key = existingKey() ?: throw IllegalStateException(Texts.t("engine_secrets_key_missing"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretsException(Texts.t("engine_secrets_decrypt_failed", e.message ?: e.javaClass.simpleName), e)
        }
    }
}

/** Decrypted view of the stored credentials. */
val Account.plainPassword: String? get() = Secrets.decrypt(password)
val Account.plainApiKey: String? get() = Secrets.decrypt(apiKey)
val Account.plainCookies: String? get() = Secrets.decrypt(cookies)
