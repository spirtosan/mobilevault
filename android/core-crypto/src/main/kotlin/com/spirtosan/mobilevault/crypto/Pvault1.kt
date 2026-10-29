/*
 * Mobile Vault – core crypto
 * Copyright (C) 2026  github.com/spirtosan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.spirtosan.mobilevault.crypto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Desktop Shu PassVault v1.3 format (import / export only).
 *
 *   "PVAULT1\0" | salt(16) | nonce(12) | AES-256-GCM(ciphertext||tag), no AAD
 *   key = PBKDF2-HMAC-SHA256(UTF-8 password, salt, 600 000, 32)
 *   plaintext = {"entries":[{"s","u","p","url"}, ...]}
 */
object Pvault1 {
    val MAGIC = byteArrayOf(0x50, 0x56, 0x41, 0x55, 0x4C, 0x54, 0x31, 0x00) // PVAULT1\0
    const val ITERATIONS = 600_000
    private const val SALT_LEN = 16
    private const val HEADER_LEN = 8 + SALT_LEN + Crypto.NONCE_LEN

    fun isPvault1(raw: ByteArray) = raw.size >= 8 && raw.copyOfRange(0, 8).contentEquals(MAGIC)

    /** Decrypt a desktop vault. Every entry gets a fresh UUID and "now" timestamps. */
    fun decrypt(password: CharArray, raw: ByteArray, iterations: Int = ITERATIONS): VaultPayload {
        if (!isPvault1(raw)) throw VaultFormatException("not a PVAULT1 file")
        if (raw.size < HEADER_LEN + 16) throw VaultFormatException("file truncated")
        val salt = raw.copyOfRange(8, 24)
        val nonce = raw.copyOfRange(24, 36)
        val pw = password.toUtf8(nfc = false)        // desktop uses the raw UTF-8 bytes
        val key = try { Crypto.pbkdf2(pw, salt, iterations) } finally { pw.wipe() }
        val plain = try {
            Crypto.open(key, nonce, raw.copyOfRange(36, raw.size))
        } finally { key.wipe() }
        try {
            return VaultPayload.fromJsonBytes(plain)
        } finally { plain.wipe() }
    }

    /** Export for the desktop app. Only s/u/p/url of non-deleted entries survive. */
    fun encrypt(password: CharArray, payload: VaultPayload, iterations: Int = ITERATIONS): ByteArray {
        val json = JsonObject(mapOf("entries" to JsonArray(payload.activeEntries.map {
            JsonObject(mapOf("s" to JsonPrimitive(it.service), "u" to JsonPrimitive(it.username),
                "p" to JsonPrimitive(it.password), "url" to JsonPrimitive(it.url)))
        }))).toString().toByteArray(Charsets.UTF_8)
        val salt = Crypto.random(SALT_LEN)
        val nonce = Crypto.random(Crypto.NONCE_LEN)
        val pw = password.toUtf8(nfc = false)
        val key = try { Crypto.pbkdf2(pw, salt, iterations) } finally { pw.wipe() }
        try {
            return MAGIC + salt + nonce + Crypto.seal(key, nonce, json)
        } finally { key.wipe(); json.wipe() }
    }
}
