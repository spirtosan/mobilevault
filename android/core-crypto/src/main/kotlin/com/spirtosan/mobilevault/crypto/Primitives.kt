/*
 * Mobile Vault – core crypto
 * Copyright (C) 2026  github.com/spirtosan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.spirtosan.mobilevault.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a key/password does not open a vault (GCM authentication failed). */
class WrongKeyException(msg: String = "wrong password or key") : Exception(msg)

/** Thrown when bytes are not a valid vault file of the expected format. */
class VaultFormatException(msg: String) : Exception(msg)

internal object Crypto {
    private val rng = SecureRandom()

    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_BITS = 128

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** AES-256-GCM. Output = ciphertext || 16-byte tag (same layout as Python's AESGCM). */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) c.updateAAD(aad)
        try {
            return c.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongKeyException()
        }
    }

    /** PBKDF2-HMAC-SHA256 over explicit UTF-8 bytes (identical on JVM, Android and Python). */
    fun pbkdf2(passwordUtf8: ByteArray, salt: ByteArray, iterations: Int, len: Int = KEY_LEN): ByteArray {
        val g = PKCS5S2ParametersGenerator(SHA256Digest())
        g.init(passwordUtf8, salt, iterations)
        return (g.generateDerivedParameters(len * 8) as KeyParameter).key
    }

    fun argon2id(secret: ByteArray, salt: ByteArray, mKiB: Int, t: Int, p: Int, len: Int = KEY_LEN): ByteArray =
        Argon2id.hash(secret, salt, mKiB, t, p, len)

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = KEY_LEN): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < len) {
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        prk.wipe()
        return out
    }

    fun sha256(b: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(b)
}

/** Encode a password to UTF-8 without creating an immutable String. Caller must wipe() the result.
 *  [nfc] = Unicode NFC normalisation (used by PVAULT2 so different keyboards give the same key). */
internal fun CharArray.toUtf8(nfc: Boolean): ByteArray {
    val chars: CharArray = if (nfc) {
        // Note: Normalizer returns a String internally, so a short-lived immutable copy exists (best effort).
        Normalizer.normalize(CharBuffer.wrap(this), Normalizer.Form.NFC).toCharArray()
    } else this
    val enc = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val bb = enc.encode(CharBuffer.wrap(chars))
    val out = ByteArray(bb.remaining()).also { bb.get(it) }
    if (bb.hasArray()) bb.array().fill(0)
    if (chars !== this) chars.fill('\u0000')
    return out
}

fun ByteArray.wipe() = fill(0)
fun CharArray.wipe() = fill('\u0000')

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
internal fun String.unb64(): ByteArray = try {
    Base64.getDecoder().decode(this)
} catch (e: IllegalArgumentException) {
    throw VaultFormatException("bad base64")
}
