package com.spirtosan.mobilevault.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.spirtosan.mobilevault.backup.JsonFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint unlock: the vault's DEK wrapped with an Android Keystore AES-GCM key that needs a
 * strong biometric for **every** use and is invalidated when a new fingerprint is enrolled.
 * The wrapped blob (`bio.json`: vault id, iv, ciphertext) lives in app-private files.
 */
class BiometricVault(filesDir: File) {
    private val blob = File(filesDir, "bio.json")

    /** The Keystore key is gone or was invalidated by a new fingerprint. */
    class Invalidated : Exception()

    fun isEnabled() = blob.isFile && keyStore().containsAlias(ALIAS)

    fun vaultId(): String? = JsonFiles.read(blob)?.let { (it["vault_id"] as? JsonPrimitive)?.contentOrNull }

    /** A new key (any old one is deleted) and a cipher to wrap the DEK, to be unlocked by BiometricPrompt. */
    fun encryptCipher(): Cipher {
        disable()
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)   // auth per use
            }
            .build()
        g.init(spec)
        val key = g.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** After BiometricPrompt succeeded with [encryptCipher]: wrap and store. [dek] is wiped. */
    fun store(authenticated: Cipher, dek: ByteArray, vaultId: String) {
        try {
            val ct = authenticated.doFinal(dek)
            JsonFiles.write(blob, JsonObject(mapOf(
                "vault_id" to JsonPrimitive(vaultId),
                "iv" to JsonPrimitive(b64(authenticated.iv)),
                "ct" to JsonPrimitive(b64(ct)),
            )))
        } finally { dek.fill(0) }
    }

    /** Cipher to unwrap the DEK; throws [Invalidated] if a new fingerprint was enrolled (then call [disable]). */
    fun decryptCipher(): Cipher {
        val o = JsonFiles.read(blob) ?: throw Invalidated()
        val key = try { keyStore().getKey(ALIAS, null) as? SecretKey } catch (e: Exception) { null } ?: throw Invalidated()
        val iv = unb64((o["iv"] as? JsonPrimitive)?.contentOrNull ?: throw Invalidated())
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) { throw Invalidated() }
    }

    /** After BiometricPrompt succeeded with [decryptCipher]. The caller owns (and wipes) the DEK. */
    fun unwrap(authenticated: Cipher): ByteArray {
        val o = JsonFiles.read(blob) ?: throw Invalidated()
        return authenticated.doFinal(unb64((o["ct"] as? JsonPrimitive)?.contentOrNull ?: throw Invalidated()))
    }

    /** Turn off: delete the wrapped DEK and the key. */
    fun disable() {
        blob.delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        const val ALIAS = "mvault-biometric"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
