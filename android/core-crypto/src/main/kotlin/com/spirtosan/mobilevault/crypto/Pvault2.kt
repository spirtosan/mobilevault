/*
 * Mobile Vault – core crypto
 * Copyright (C) 2026  github.com/spirtosan
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.spirtosan.mobilevault.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.util.UUID

/** Argon2id cost. Defaults: 64 MiB, 3 passes, 4 lanes (see docs/VAULT_FORMAT_V2.md). Files with any p in 1..16 open. */
data class Argon2Params(val mKiB: Int = 65536, val t: Int = 3, val p: Int = 4) {
    init {
        require(mKiB in 8..4 * 1024 * 1024 && t in 1..64 && p in 1..16 && mKiB >= 8 * p) { "Argon2 parameters out of range" }
    }

    companion object {
        /** Lowest parameters accepted when *reading* a file (protects against downgraded headers). */
        val MINIMUM = Argon2Params(mKiB = 32768, t = 2, p = 1)
    }
}

/** One key slot: the DEK wrapped by a key derived from a password or a recovery key. */
data class KeySlot(
    val type: String,                 // "password" | "recovery"
    val kdf: JsonObject,
    val nonce: ByteArray,
    val wrappedDek: ByteArray,
) {
    internal fun toJson() = JsonObject(linkedMapOf(
        "type" to JsonPrimitive(type), "kdf" to kdf,
        "nonce" to JsonPrimitive(nonce.b64()), "wrapped_dek" to JsonPrimitive(wrappedDek.b64()),
    ))

    override fun equals(other: Any?) = other is KeySlot && type == other.type && kdf == other.kdf &&
        nonce.contentEquals(other.nonce) && wrappedDek.contentEquals(other.wrappedDek)

    override fun hashCode() = type.hashCode() * 31 + wrappedDek.contentHashCode()

    internal companion object {
        fun fromJson(o: JsonObject) = KeySlot(
            type = (o["type"] as? JsonPrimitive)?.contentOrNull ?: throw VaultFormatException("slot without type"),
            kdf = o["kdf"] as? JsonObject ?: throw VaultFormatException("slot without kdf"),
            nonce = ((o["nonce"] as? JsonPrimitive)?.contentOrNull ?: throw VaultFormatException("slot nonce")).unb64(),
            wrappedDek = ((o["wrapped_dek"] as? JsonPrimitive)?.contentOrNull
                ?: throw VaultFormatException("slot wrapped_dek")).unb64(),
        )
    }
}

/**
 * Plaintext header of a PVAULT2 file. It is authenticated (used as GCM AAD), but only
 * *after* a successful unlock. Values from [Pvault2.peekHeader] must be treated as untrusted.
 */
data class VaultHeader(
    val vaultId: String,
    val rev: Long,
    val updated: String,
    val device: String,
    val hint: String?,
    val slots: List<KeySlot>,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    fun slot(type: String) = slots.firstOrNull { it.type == type }

    internal fun toJsonBytes(): ByteArray = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        putAll(extra)
        put("v", JsonPrimitive(2)); put("vault_id", JsonPrimitive(vaultId)); put("rev", JsonPrimitive(rev))
        put("updated", JsonPrimitive(updated)); put("device", JsonPrimitive(device))
        if (!hint.isNullOrEmpty()) put("hint", JsonPrimitive(hint)) else remove("hint")
        put("slots", JsonArray(slots.map { it.toJson() }))
    }).toString().toByteArray(Charsets.UTF_8)

    internal companion object {
        private val KNOWN = setOf("v", "vault_id", "rev", "updated", "device", "hint", "slots")
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun fromJsonBytes(b: ByteArray): VaultHeader {
            val o = try { Json.parseToJsonElement(b.toString(Charsets.UTF_8)).jsonObject } catch (e: Exception) {
                throw VaultFormatException("header is not a JSON object")
            }
            fun prim(k: String) = o[k] as? JsonPrimitive
            if (prim("v")?.intOrNull != 2) throw VaultFormatException("unsupported header version")
            val id = prim("vault_id")?.contentOrNull ?: ""
            if (!UUID_RE.matches(id)) throw VaultFormatException("bad vault_id")
            val rev = prim("rev")?.longOrNull ?: throw VaultFormatException("bad rev")
            if (rev < 1) throw VaultFormatException("bad rev")
            val slots = (o["slots"] as? JsonArray ?: throw VaultFormatException("no slots"))
                .map { KeySlot.fromJson(it.jsonObject) }
            return VaultHeader(id, rev, prim("updated")?.contentOrNull ?: "", prim("device")?.contentOrNull ?: "",
                prim("hint")?.contentOrNull, slots, o.filterKeys { it !in KNOWN })
        }
    }
}

/**
 * An open vault. Holds the data-encryption key (DEK) in memory until [wipe].
 * Not thread-safe; use from one coroutine/thread at a time.
 */
class UnlockedVault internal constructor(
    header: VaultHeader,
    private val dek: ByteArray,
    var payload: VaultPayload,
) {
    var header: VaultHeader = header
        private set

    val vaultId get() = header.vaultId
    val rev get() = header.rev

    /**
     * Produce the next revision of the file: rev+1, new body nonce, header as AAD.
     * The result should be written atomically and uploaded as-is.
     */
    fun seal(deviceId: String): ByteArray {
        checkOpen()
        header = header.copy(rev = header.rev + 1, updated = nowIso(), device = deviceId)
        return Pvault2.encode(header, dek, payload)
    }

    /** Re-wrap the DEK for a new master password (recovery slot unchanged). Call [seal] afterwards. */
    fun changePassword(newPassword: CharArray, hint: String?, kdf: Argon2Params = Argon2Params()) {
        checkOpen()
        if (hint != null) {
            val r = HintPolicy.check(hint, newPassword)
            require(r is HintPolicy.Result.Ok) { (r as HintPolicy.Result.Rejected).reason }
        }
        val slot = Pvault2.passwordSlot(newPassword, dek, header.vaultId, kdf)
        header = header.copy(hint = hint?.ifBlank { null }, slots = header.slots.filter { it.type != "password" } + slot)
    }

    /** Replace the recovery key (e.g. if the printed kit was lost). Call [seal] afterwards. */
    fun regenerateRecoveryKey(): RecoveryKey {
        checkOpen()
        val rk = RecoveryKey.generate()
        val slot = Pvault2.recoverySlot(rk, dek, header.vaultId)
        header = header.copy(slots = header.slots.filter { it.type != "recovery" } + slot)
        return rk
    }

    /** Check a typed password without changing anything (used by the "practise your password" reminder). */
    fun verifyPassword(password: CharArray): Boolean {
        checkOpen()
        val slot = header.slot("password") ?: return false
        return try {
            val d = Pvault2.unwrap(slot, header.vaultId) { kdf -> Pvault2.passwordKek(password, kdf) }
            val same = java.security.MessageDigest.isEqual(d, dek)
            d.wipe()
            same
        } catch (e: WrongKeyException) { false }
    }

    /** Copy of the DEK, for wrapping with an Android Keystore key (biometric unlock). Caller must wipe it. */
    fun exportDekForBiometric(): ByteArray { checkOpen(); return dek.copyOf() }

    private var wiped = false
    private fun checkOpen() = check(!wiped) { "vault is locked" }

    /** Lock: zero the DEK. Entry strings are dropped by the caller (JVM strings can't be zeroed). */
    fun wipe() { dek.wipe(); wiped = true; payload = VaultPayload() }
}

/**
 * PVAULT2 file format:
 *
 *   "PVAULT2\0" | header_len (u32 BE) | header JSON | nonce(12) | AES-256-GCM(DEK, payload JSON) || tag
 *   AAD = every byte before the nonce (magic + length + header).
 *
 * Slots: AES-256-GCM(KEK, DEK), AAD = "PVAULT2-slot:<type>:<vault_id>".
 *   password: KEK = Argon2id(NFC-normalised UTF-8 password, salt)
 *   recovery: KEK = HKDF-SHA256(recovery key, salt, "PVAULT2 recovery")
 */
object Pvault2 {
    val MAGIC = byteArrayOf(0x50, 0x56, 0x41, 0x55, 0x4C, 0x54, 0x32, 0x00) // PVAULT2\0
    private const val MAX_HEADER = 64 * 1024
    private const val SALT_LEN = 16
    private val RECOVERY_INFO = "PVAULT2 recovery".toByteArray()

    fun isPvault2(raw: ByteArray) = raw.size >= 8 && raw.copyOfRange(0, 8).contentEquals(MAGIC)

    class Created(val vault: UnlockedVault, val recoveryKey: RecoveryKey, val bytes: ByteArray)

    /** Create a new vault (rev 1). Show [Created.recoveryKey] once on the Emergency Kit, then wipe it. */
    fun create(
        password: CharArray,
        deviceId: String,
        hint: String? = null,
        payload: VaultPayload = VaultPayload(),
        kdf: Argon2Params = Argon2Params(),
    ): Created {
        if (hint != null) {
            val r = HintPolicy.check(hint, password)
            require(r is HintPolicy.Result.Ok) { (r as HintPolicy.Result.Rejected).reason }
        }
        val id = UUID.randomUUID().toString()
        val dek = Crypto.random(Crypto.KEY_LEN)
        val rk = RecoveryKey.generate()
        val header = VaultHeader(
            vaultId = id, rev = 1, updated = nowIso(), device = deviceId, hint = hint?.ifBlank { null },
            slots = listOf(passwordSlot(password, dek, id, kdf), recoverySlot(rk, dek, id)),
        )
        val bytes = encode(header, dek, payload)
        return Created(UnlockedVault(header, dek, payload), rk, bytes)
    }

    /** Read the header WITHOUT authenticating it (for showing the hint / revision before unlock). */
    fun peekHeader(raw: ByteArray): VaultHeader = split(raw).header

    fun unlockWithPassword(raw: ByteArray, password: CharArray): UnlockedVault {
        val f = split(raw)
        val slot = f.header.slot("password") ?: throw VaultFormatException("no password slot")
        val dek = unwrap(slot, f.header.vaultId) { passwordKek(password, it) }
        return openBody(f, dek)
    }

    fun unlockWithRecoveryKey(raw: ByteArray, key: RecoveryKey): UnlockedVault {
        val f = split(raw)
        val slot = f.header.slot("recovery") ?: throw VaultFormatException("no recovery slot")
        val dek = unwrap(slot, f.header.vaultId) { recoveryKek(key, it) }
        return openBody(f, dek)
    }

    /** Biometric path: the DEK was unwrapped by an Android Keystore key. Takes ownership of [dek]. */
    fun unlockWithDek(raw: ByteArray, dek: ByteArray): UnlockedVault = openBody(split(raw), dek)

    /** Verify that [raw] decrypts with an already-known DEK (backup "test restore"). */
    fun verifyWithDek(raw: ByteArray, dek: ByteArray): Boolean = try {
        unlockWithDek(raw, dek.copyOf()).let { it.wipe(); true }
    } catch (e: WrongKeyException) { false } catch (e: VaultFormatException) { false }

    // ── internals ─────────────────────────────────────────────────────────────

    private class Parts(val header: VaultHeader, val aad: ByteArray, val nonce: ByteArray, val ct: ByteArray)

    private fun split(raw: ByteArray): Parts {
        if (!isPvault2(raw)) throw VaultFormatException("not a PVAULT2 file")
        if (raw.size < 12) throw VaultFormatException("file truncated")
        val hlen = ByteBuffer.wrap(raw, 8, 4).int
        if (hlen <= 0 || hlen > MAX_HEADER) throw VaultFormatException("bad header length")
        val bodyStart = 12 + hlen
        if (raw.size < bodyStart + Crypto.NONCE_LEN + 16) throw VaultFormatException("file truncated")
        val header = VaultHeader.fromJsonBytes(raw.copyOfRange(12, bodyStart))
        return Parts(header, raw.copyOfRange(0, bodyStart),
            raw.copyOfRange(bodyStart, bodyStart + Crypto.NONCE_LEN), raw.copyOfRange(bodyStart + Crypto.NONCE_LEN, raw.size))
    }

    private fun openBody(f: Parts, dek: ByteArray): UnlockedVault {
        val plain = try {
            Crypto.open(dek, f.nonce, f.ct, f.aad)
        } catch (e: WrongKeyException) {
            dek.wipe()
            throw WrongKeyException("vault body failed authentication (wrong key or tampered file)")
        }
        try {
            return UnlockedVault(f.header, dek, VaultPayload.fromJsonBytes(plain))
        } finally { plain.wipe() }
    }

    internal fun encode(header: VaultHeader, dek: ByteArray, payload: VaultPayload): ByteArray {
        val h = header.toJsonBytes()
        require(h.size <= MAX_HEADER) { "header too large" }
        val aad = MAGIC + ByteBuffer.allocate(4).putInt(h.size).array() + h
        val nonce = Crypto.random(Crypto.NONCE_LEN)
        val plain = payload.toJsonBytes()
        try {
            return aad + nonce + Crypto.seal(dek, nonce, plain, aad)
        } finally { plain.wipe() }
    }

    private fun slotAad(type: String, vaultId: String) = "PVAULT2-slot:$type:$vaultId".toByteArray()

    internal fun passwordKek(password: CharArray, kdf: JsonObject): ByteArray {
        if ((kdf["alg"] as? JsonPrimitive)?.contentOrNull != "argon2id") throw VaultFormatException("unsupported password KDF")
        val m = (kdf["m_kib"] as? JsonPrimitive)?.intOrNull ?: throw VaultFormatException("kdf m_kib")
        val t = (kdf["t"] as? JsonPrimitive)?.intOrNull ?: throw VaultFormatException("kdf t")
        val p = (kdf["p"] as? JsonPrimitive)?.intOrNull ?: throw VaultFormatException("kdf p")
        if (m < minimum.mKiB || t < minimum.t) throw VaultFormatException("KDF parameters below the allowed minimum")
        val params = try { Argon2Params(m, t, p) } catch (e: IllegalArgumentException) { throw VaultFormatException("kdf range") }
        val salt = ((kdf["salt"] as? JsonPrimitive)?.contentOrNull ?: throw VaultFormatException("kdf salt")).unb64()
        val pw = password.toUtf8(nfc = true)
        try {
            return Crypto.argon2id(pw, salt, params.mKiB, params.t, params.p)
        } finally { pw.wipe() }
    }

    private fun recoveryKek(key: RecoveryKey, kdf: JsonObject): ByteArray {
        if ((kdf["alg"] as? JsonPrimitive)?.contentOrNull != "hkdf-sha256") throw VaultFormatException("unsupported recovery KDF")
        val salt = ((kdf["salt"] as? JsonPrimitive)?.contentOrNull ?: throw VaultFormatException("kdf salt")).unb64()
        val m = key.material()
        try { return Crypto.hkdf(m, salt, RECOVERY_INFO) } finally { m.wipe() }
    }

    internal fun unwrap(slot: KeySlot, vaultId: String, kek: (JsonObject) -> ByteArray): ByteArray {
        val k = kek(slot.kdf)
        try {
            return Crypto.open(k, slot.nonce, slot.wrappedDek, slotAad(slot.type, vaultId))
        } finally { k.wipe() }
    }

    private fun wrap(type: String, kdf: JsonObject, kek: ByteArray, dek: ByteArray, vaultId: String): KeySlot {
        val nonce = Crypto.random(Crypto.NONCE_LEN)
        try {
            return KeySlot(type, kdf, nonce, Crypto.seal(kek, nonce, dek, slotAad(type, vaultId)))
        } finally { kek.wipe() }
    }

    internal fun passwordSlot(password: CharArray, dek: ByteArray, vaultId: String, p: Argon2Params): KeySlot {
        val kdf = JsonObject(linkedMapOf("alg" to JsonPrimitive("argon2id"), "m_kib" to JsonPrimitive(p.mKiB),
            "t" to JsonPrimitive(p.t), "p" to JsonPrimitive(p.p), "salt" to JsonPrimitive(Crypto.random(SALT_LEN).b64())))
        return wrap("password", kdf, passwordKek(password, kdf), dek, vaultId)
    }

    internal fun recoverySlot(key: RecoveryKey, dek: ByteArray, vaultId: String): KeySlot {
        val kdf = JsonObject(linkedMapOf("alg" to JsonPrimitive("hkdf-sha256"),
            "salt" to JsonPrimitive(Crypto.random(SALT_LEN).b64())))
        return wrap("recovery", kdf, recoveryKek(key, kdf), dek, vaultId)
    }

    /** Minimum accepted Argon2 cost when reading. Tests may lower it; the app must not. */
    @Volatile
    internal var minimum: Argon2Params = Argon2Params.MINIMUM
}
