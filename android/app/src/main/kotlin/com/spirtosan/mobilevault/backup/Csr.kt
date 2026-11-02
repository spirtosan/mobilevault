package com.spirtosan.mobilevault.backup

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Hand-built PKCS#10 certificate request (RFC 2986) for an EC P-256 key, signed with SHA256withECDSA.
 * The private key never leaves Android Keystore: [build] only needs the public key (X.509
 * SubjectPublicKeyInfo DER, i.e. `PublicKey.encoded`) and a signing function.
 * The server checks it with `openssl req -verify` and accepts only P-256/P-384 (or RSA ≥ 2048).
 */
object Csr {
    /** 1.2.840.10045.4.3.2 ecdsa-with-SHA256 */
    private val ECDSA_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    /** 2.5.4.3 commonName */
    private val CN = byteArrayOf(0x55, 0x04, 0x03)

    fun build(commonName: String, subjectPublicKeyInfo: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
        val name = seq(set(seq(tlv(0x06, CN), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val info = seq(tlv(0x02, byteArrayOf(0)), name, subjectPublicKeyInfo, tlv(0xA0, ByteArray(0)))
        val algorithm = seq(tlv(0x06, ECDSA_SHA256))
        val signature = sign(info)                       // DER ECDSA-Sig-Value from java.security.Signature
        return seq(info, algorithm, tlv(0x03, byteArrayOf(0) + signature))
    }

    fun pem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE REQUEST-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END CERTIFICATE REQUEST-----\n"

    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))

    private fun concat(parts: Array<out ByteArray>): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    internal fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> throw IllegalArgumentException("DER element too large")
        }
        out.write(content)
        return out.toByteArray()
    }
}
