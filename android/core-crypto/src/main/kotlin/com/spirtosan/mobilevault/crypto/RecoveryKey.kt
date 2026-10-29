/*
 * Mobile Vault – core crypto
 * Copyright (C) 2026  github.com/spirtosan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.spirtosan.mobilevault.crypto

/**
 * Recovery key printed on the Emergency Kit: 160 random bits, written as
 * 32 Crockford-Base32 characters + a 4-character checksum, in 9 groups of 4:
 *
 *     K7QM-2XRA-…-9FJD   (36 chars, ambiguous letters O/I/L are accepted as 0/1/1)
 *
 * The checksum catches typos before the (slow) unlock attempt.
 */
class RecoveryKey private constructor(private val bytes: ByteArray) {

    /** Human form with dashes. */
    fun display(): String = encodeWithChecksum(bytes).chunked(4).joinToString("-")

    internal fun material(): ByteArray = bytes.copyOf()

    fun wipe() = bytes.wipe()

    override fun toString() = "RecoveryKey(***)"

    companion object {
        const val BYTES = 20
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford

        fun generate() = RecoveryKey(Crypto.random(BYTES))

        /** Parse user input. Throws IllegalArgumentException with a user-readable message. */
        fun parse(input: String): RecoveryKey {
            val s = input.uppercase().filter { !it.isWhitespace() && it != '-' }
                .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
            require(s.length == 36) { "A recovery key has 36 characters (got ${s.length})" }
            require(s.all { it in ALPHABET }) { "The recovery key contains an invalid character" }
            val key = base32Decode(s.substring(0, 32))
            require(checksum(key) == s.substring(32)) { "Recovery key checksum does not match – check for a typo" }
            return RecoveryKey(key)
        }

        private fun checksum(key: ByteArray): String {
            val h = Crypto.sha256(key)
            val v = ((h[0].toInt() and 0xFF) shl 12) or ((h[1].toInt() and 0xFF) shl 4) or ((h[2].toInt() and 0xFF) ushr 4)
            return (0 until 4).map { ALPHABET[(v ushr (15 - 5 * it)) and 31] }.joinToString("")
        }

        private fun encodeWithChecksum(key: ByteArray) = base32Encode(key) + checksum(key)

        private fun base32Encode(b: ByteArray): String {
            val sb = StringBuilder()
            var buf = 0; var bits = 0
            for (x in b) {
                buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
                while (bits >= 5) { sb.append(ALPHABET[(buf ushr (bits - 5)) and 31]); bits -= 5 }
            }
            require(bits == 0)
            return sb.toString()
        }

        private fun base32Decode(s: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var buf = 0; var bits = 0
            for (c in s) {
                buf = (buf shl 5) or ALPHABET.indexOf(c); bits += 5
                if (bits >= 8) { out.write((buf ushr (bits - 8)) and 0xFF); bits -= 8 }
            }
            return out.toByteArray()
        }
    }
}

/** Rules for the optional master-password hint (stored in plaintext in the vault header). */
object HintPolicy {
    const val MAX_LEN = 100

    sealed class Result {
        object Ok : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun check(hint: String, password: CharArray): Result {
        if (hint.length > MAX_LEN) return Result.Rejected("Hint is longer than $MAX_LEN characters")
        if (hint.isBlank()) return Result.Ok
        val h = hint.lowercase()
        val p = String(password).lowercase()   // short-lived; unavoidable for substring search
        if (p.isNotEmpty() && h.contains(p)) return Result.Rejected("The hint must not contain the password")
        val n = 4
        if (p.length >= n) {
            for (i in 0..p.length - n) {
                if (h.contains(p.substring(i, i + n)))
                    return Result.Rejected("The hint must not contain parts of the password")
            }
        }
        return Result.Ok
    }
}
