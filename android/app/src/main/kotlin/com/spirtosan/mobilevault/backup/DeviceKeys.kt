package com.spirtosan.mobilevault.backup

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Android Keystore keys of the backup client. Nothing here can be exported:
 * - the client key (EC P-256, StrongBox if the phone has it) signs the CSR and the TLS handshake;
 *   after pairing it carries the issued certificate chain;
 * - a separate AES-GCM key encrypts the bearer token stored in app-private files.
 */
class DeviceKeys(private val context: Context) {
    private fun ks() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    class Generated(val publicKey: PublicKey, val strongBox: Boolean)

    fun generateClientKey(alias: String): Generated {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // NONE: TLS signs a precomputed handshake hash through the Keystore key; SHA-256: the CSR.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            try {
                kpg.initialize(spec(true))
                return Generated(kpg.generateKeyPair().public, true)
            } catch (e: StrongBoxUnavailableException) { /* fall back to the TEE */ }
        }
        kpg.initialize(spec(false))
        return Generated(kpg.generateKeyPair().public, false)
    }

    fun sign(alias: String, data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(ks().getKey(alias, null) as PrivateKey)
        update(data)
        sign()
    }

    /** Put the issued certificate (and the CA) on the key entry. Refuses a certificate for another key. */
    fun installChain(alias: String, certPem: String, caPem: String) {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate
        val ca = cf.generateCertificate(caPem.byteInputStream()) as X509Certificate
        val ks = ks()
        val mine = ks.getCertificate(alias)?.publicKey ?: throw IllegalStateException("client key missing")
        if (!mine.encoded.contentEquals(cert.publicKey.encoded)) throw IllegalStateException("certificate is for another key")
        ks.setKeyEntry(alias, ks.getKey(alias, null), null, arrayOf(cert, ca))
    }

    fun hasKey(alias: String) = runCatching { ks().containsAlias(alias) }.getOrDefault(false)

    fun delete(alias: String) { runCatching { ks().deleteEntry(alias) } }

    /** Client certificate for mTLS: always our one alias; the private key stays inside Keystore. */
    fun keyManager(alias: String): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun getCertificateChain(a: String?): Array<X509Certificate>? =
            ks().getCertificateChain(alias)?.map { it as X509Certificate }?.toTypedArray()
        override fun getPrivateKey(a: String?) = ks().getKey(alias, null) as? PrivateKey
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    // ── token encryption ───────────────────────────────────────────────────────
    private fun tokenKey(): SecretKey {
        (ks().getKey(TOKEN_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        g.init(KeyGenParameterSpec.Builder(TOKEN_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    /** iv(12) || AES-GCM(ciphertext + tag) */
    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, tokenKey())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, tokenKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    fun deleteTokenKey() = delete(TOKEN_ALIAS)

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CLIENT_ALIAS_PREFIX = "mvault-client-"
        const val TOKEN_ALIAS = "mvault-token"
    }
}
