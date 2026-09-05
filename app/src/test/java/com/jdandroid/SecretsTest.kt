package com.jdandroid

import com.jdandroid.data.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of [Secrets] that do not need the Android Keystore: prefix
 * detection, plaintext values from older installations, and the rule that a
 * missing Keystore never turns into plaintext storage or an empty password.
 * On the JVM there is no AndroidKeyStore provider, which stands in for a
 * broken Keystore on the device.
 */
class SecretsTest {

    @Test
    fun nurDerPraefixKennzeichnetVerschluesselteWerte() {
        assertTrue(Secrets.isEncrypted("enc1:AAAA"))
        assertFalse(Secrets.isEncrypted("geheim"))
        assertFalse(Secrets.isEncrypted(" enc1:AAAA"))
        assertFalse(Secrets.isEncrypted("ENC1:AAAA"))
        assertFalse(Secrets.isEncrypted(""))
        assertFalse(Secrets.isEncrypted(null))
    }

    @Test
    fun klartextAusAltenInstallationenWirdUnveraendertGelesen() {
        assertEquals("geheim", Secrets.decrypt("geheim"))
        assertEquals("enc2:x", Secrets.decrypt("enc2:x"))
        assertEquals("", Secrets.decrypt(""))
        assertNull(Secrets.decrypt(null))
    }

    @Test
    fun leereWerteBrauchenKeinenKeystore() {
        assertNull(Secrets.encrypt(null))
        assertEquals("", Secrets.encrypt(""))
    }

    @Test
    fun ohneKeystoreWirdNichtsImKlartextGespeichert() {
        val e = assertThrows(Secrets.SecretsException::class.java) { Secrets.encrypt("geheim") }
        assertTrue(e.message!!, e.message!!.contains("Keystore"))
    }

    @Test
    fun unlesbarerVerschluesselterWertIstEinFehlerKeinLeeresPasswort() {
        val e = assertThrows(Secrets.SecretsException::class.java) { Secrets.decrypt("enc1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") }
        assertTrue(e.message!!, e.message!!.contains("nicht lesbar"))
        // Not even base64: still the same clear error
        assertThrows(Secrets.SecretsException::class.java) { Secrets.decrypt("enc1:%%%") }
    }
}
