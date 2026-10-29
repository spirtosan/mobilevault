package com.spirtosan.mobilevault.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Fast Argon2 parameters for tests only. The production minimum is enforced in [Pvault2MinimumTest]. */
private val FAST = Argon2Params(mKiB = 256, t = 1, p = 1)

private fun res(name: String): ByteArray =
    CryptoTest::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() }

private fun resJson(name: String): JsonObject = Json.parseToJsonElement(res(name).toString(Charsets.UTF_8)).jsonObject

class CryptoTest {
    @BeforeEach
    fun lowerMinimum() { Pvault2.minimum = Argon2Params(8, 1, 1) }

    // ── PVAULT1 (desktop) ─────────────────────────────────────────────────────
    @Test
    fun pvault1_decryptsDesktopSample() {
        val p = Pvault1.decrypt("correct horse battery staple".toCharArray(), res("sample-pvault1.bin"))
        val exp = resJson("sample-pvault1.expected.json")["entries"]!!.jsonArray
        assertEquals(exp.size, p.entries.size)
        exp.forEachIndexed { i, e ->
            val o = e.jsonObject
            assertEquals(o["s"]!!.jsonPrimitive.content, p.entries[i].service)
            assertEquals(o["u"]!!.jsonPrimitive.content, p.entries[i].username)
            assertEquals(o["p"]!!.jsonPrimitive.content, p.entries[i].password)
            assertEquals(o["url"]!!.jsonPrimitive.content, p.entries[i].url)
        }
        assertEquals(p.entries.size, p.entries.map { it.id }.toSet().size, "every imported entry gets a unique id")
    }

    @Test
    fun pvault1_wrongPasswordRejected() {
        assertThrows(WrongKeyException::class.java) {
            Pvault1.decrypt("correct horse battery stapl".toCharArray(), res("sample-pvault1.bin"))
        }
    }

    @Test
    fun pvault1_unicodePasswordMatchesPython() {
        val meta = resJson("unicode-pw-pvault1.json")
        val p = Pvault1.decrypt(meta["password"]!!.jsonPrimitive.content.toCharArray(), res("unicode-pw-pvault1.bin"))
        assertEquals("п@рола", p.entries.single().password)
    }

    @Test
    fun pvault1_exportRoundTripDropsDeleted() {
        val payload = VaultPayload(listOf(
            Entry(service = "A", password = "1"), Entry(service = "B", password = "2", deleted = true)))
        val raw = Pvault1.encrypt("pw".toCharArray(), payload, iterations = 1000)
        assertTrue(Pvault1.isPvault1(raw))
        val back = Pvault1.decrypt("pw".toCharArray(), raw, iterations = 1000)
        assertEquals(listOf("A"), back.entries.map { it.service })
    }

    @Test
    fun pvault1_rejectsGarbage() {
        assertThrows(VaultFormatException::class.java) { Pvault1.decrypt("x".toCharArray(), ByteArray(50)) }
        assertThrows(VaultFormatException::class.java) { Pvault1.decrypt("x".toCharArray(), Pvault1.MAGIC + ByteArray(10)) }
    }

    // ── PVAULT2 ───────────────────────────────────────────────────────────────
    private fun sampleEntries() = VaultPayload(listOf(
        Entry(service = "GitHub", username = "me", password = "s3cret", url = "https://github.com", tags = listOf("dev")),
        Entry(service = "Банка", password = "Žluť 🐎", notes = "PIN in the safe", favourite = true)),
        settings = mapOf("clipboard_timeout" to JsonPrimitive(30)))

    @Test
    fun pvault2_createUnlockRoundTrip() {
        val payload = sampleEntries()
        val c = Pvault2.create("master pw".toCharArray(), "phone-1", hint = "the usual", payload = payload, kdf = FAST)
        assertTrue(Pvault2.isPvault2(c.bytes))
        val v = Pvault2.unlockWithPassword(c.bytes, "master pw".toCharArray())
        assertEquals(payload, v.payload)
        assertEquals(1L, v.rev)
        assertEquals("the usual", Pvault2.peekHeader(c.bytes).hint)
        assertEquals(c.vault.vaultId, v.vaultId)
    }

    @Test
    fun pvault2_wrongPassword() {
        val c = Pvault2.create("master pw".toCharArray(), "d", kdf = FAST)
        assertThrows(WrongKeyException::class.java) { Pvault2.unlockWithPassword(c.bytes, "Master pw".toCharArray()) }
    }

    @Test
    fun pvault2_recoveryKeyUnlocks_andSurvivesPasswordChange() {
        val c = Pvault2.create("old".toCharArray(), "d", payload = sampleEntries(), kdf = FAST)
        val rkText = c.recoveryKey.display()
        val v = c.vault
        v.changePassword("new password".toCharArray(), hint = null, kdf = FAST)
        val raw2 = v.seal("d")
        assertThrows(WrongKeyException::class.java) { Pvault2.unlockWithPassword(raw2, "old".toCharArray()) }
        assertEquals(2, Pvault2.unlockWithPassword(raw2, "new password".toCharArray()).payload.entries.size)
        val viaRk = Pvault2.unlockWithRecoveryKey(raw2, RecoveryKey.parse(rkText.lowercase()))
        assertEquals(sampleEntries().entries.map { it.service }, viaRk.payload.entries.map { it.service })
        assertNull(Pvault2.peekHeader(raw2).hint)
    }

    @Test
    fun pvault2_forgottenPasswordFlow_recoveryThenNewPassword() {
        val c = Pvault2.create("forgotten".toCharArray(), "d", kdf = FAST)
        val v = Pvault2.unlockWithRecoveryKey(c.bytes, RecoveryKey.parse(c.recoveryKey.display()))
        v.changePassword("brand new".toCharArray(), "hint", FAST)
        val raw = v.seal("d")
        assertEquals(c.vault.vaultId, Pvault2.unlockWithPassword(raw, "brand new".toCharArray()).vaultId)
    }

    @Test
    fun pvault2_regenerateRecoveryKeyInvalidatesOld() {
        val c = Pvault2.create("pw".toCharArray(), "d", kdf = FAST)
        val old = c.recoveryKey.display()
        val newKey = c.vault.regenerateRecoveryKey().display()
        val raw = c.vault.seal("d")
        assertNotEquals(old, newKey)
        assertThrows(WrongKeyException::class.java) { Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(old)) }
        Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(newKey))
    }

    @Test
    fun pvault2_sealIncrementsRevisionAndNonce() {
        val c = Pvault2.create("pw".toCharArray(), "d", kdf = FAST)
        val v = Pvault2.unlockWithPassword(c.bytes, "pw".toCharArray())
        v.payload = v.payload.copy(entries = v.payload.entries + Entry(service = "X", password = "y"))
        val r2 = v.seal("phone-2")
        val r3 = v.seal("phone-2")
        assertEquals(2L, Pvault2.peekHeader(r2).rev)
        assertEquals(3L, Pvault2.peekHeader(r3).rev)
        assertEquals("phone-2", Pvault2.peekHeader(r3).device)
        assertFalse(r2.contentEquals(r3))
        assertEquals("X", Pvault2.unlockWithPassword(r3, "pw".toCharArray()).payload.entries.single().service)
    }

    @Test
    fun pvault2_headerTamperingDetected() {
        val c = Pvault2.create("pw".toCharArray(), "d", hint = "a hint", kdf = FAST)
        val raw = c.bytes
        // change the hint text inside the plaintext header (same length → structure stays valid)
        val s = String(raw, Charsets.ISO_8859_1).replace("a hint", "b hint")
        val tampered = s.toByteArray(Charsets.ISO_8859_1)
        assertEquals("b hint", Pvault2.peekHeader(tampered).hint)        // peek is unauthenticated…
        assertThrows(WrongKeyException::class.java) {                      // …but unlock detects it
            Pvault2.unlockWithPassword(tampered, "pw".toCharArray())
        }
    }

    @Test
    fun pvault2_bodyTamperingDetected() {
        val raw = Pvault2.create("pw".toCharArray(), "d", kdf = FAST).bytes.copyOf()
        raw[raw.size - 20] = (raw[raw.size - 20].toInt() xor 1).toByte()
        assertThrows(WrongKeyException::class.java) { Pvault2.unlockWithPassword(raw, "pw".toCharArray()) }
    }

    @Test
    fun pvault2_unlockWithDekAndVerify() {
        val c = Pvault2.create("pw".toCharArray(), "d", payload = sampleEntries(), kdf = FAST)
        val dek = c.vault.exportDekForBiometric()
        assertTrue(Pvault2.verifyWithDek(c.bytes, dek))
        assertEquals(2, Pvault2.unlockWithDek(c.bytes, dek.copyOf()).payload.entries.size)
        assertFalse(Pvault2.verifyWithDek(c.bytes, ByteArray(32)))
    }

    @Test
    fun pvault2_verifyPassword_forReminder() {
        val c = Pvault2.create("Secret One".toCharArray(), "d", kdf = FAST)
        assertTrue(c.vault.verifyPassword("Secret One".toCharArray()))
        assertFalse(c.vault.verifyPassword("secret one".toCharArray()))
    }

    @Test
    fun pvault2_unicodeNormalisation() {
        val composed = "café".toCharArray()        // é as one code point
        val decomposed = "café".toCharArray()     // e + combining accent (some keyboards)
        val c = Pvault2.create(composed, "d", kdf = FAST)
        Pvault2.unlockWithPassword(c.bytes, decomposed)
    }

    @Test
    fun pvault2_unknownFieldsPreserved() {
        val e = Entry.fromJson(Json.parseToJsonElement(
            """{"s":"A","p":"b","future":{"k":1}}""").jsonObject, "2026-01-01T00:00:00Z")
        val c = Pvault2.create("pw".toCharArray(), "d", payload = VaultPayload(listOf(e)), kdf = FAST)
        val back = Pvault2.unlockWithPassword(c.bytes, "pw".toCharArray()).payload.entries.single()
        assertEquals(e.extra, back.extra)
        assertEquals("2026-01-01T00:00:00Z", back.created)
    }

    @Test
    fun pvault2_lockedVaultCannotSeal() {
        val v = Pvault2.create("pw".toCharArray(), "d", kdf = FAST).vault
        v.wipe()
        assertThrows(IllegalStateException::class.java) { v.seal("d") }
    }

    @Test
    fun pvault2_hintContainingPasswordRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Pvault2.create("Tiger2026".toCharArray(), "d", hint = "my tiger", kdf = FAST)
        }
    }

    @Test
    fun pvault2_garbageRejected() {
        assertThrows(VaultFormatException::class.java) { Pvault2.peekHeader(ByteArray(100)) }
        assertThrows(VaultFormatException::class.java) { Pvault2.peekHeader(Pvault2.MAGIC + byteArrayOf(0x7f, 0, 0, 0)) }
    }

    // ── Cross-implementation (Python reference ↔ Kotlin) ──────────────────────
    @Test
    fun crossImpl_kotlinOpensPythonVault() {
        Pvault2.minimum = Argon2Params.MINIMUM            // the vector uses the real minimum (32 MiB, t=2)
        val meta = resJson("sample-pvault2.json")
        val raw = res("sample-pvault2.bin")
        val pw = meta["password"]!!.jsonPrimitive.content.toCharArray()
        val v = Pvault2.unlockWithPassword(raw, pw)
        val exp = meta["expected"]!!.jsonObject
        val expEntries = exp["entries"]!!.jsonArray
        assertEquals(expEntries.size, v.payload.entries.size)
        assertEquals("Žluťoučký kůň 🐎", v.payload.entries[1].password)
        assertEquals(expEntries[0].jsonObject["future_field"], v.payload.entries[0].extra["future_field"])
        assertEquals(exp["future_top"], v.payload.extra["future_top"])
        assertEquals(meta["hint"]!!.jsonPrimitive.content, Pvault2.peekHeader(raw).hint)
        val viaRk = Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(meta["recovery_key"]!!.jsonPrimitive.content))
        assertEquals(v.payload, viaRk.payload)
        // Kotlin re-seals; result must still open with the same password (Python check happens in tools/)
        val resealed = v.seal("kotlin")
        assertEquals(2L, Pvault2.unlockWithPassword(resealed, pw).rev)
    }
}

class Pvault2MinimumTest {
    @Test
    fun downgradedKdfParametersRefused() {
        Pvault2.minimum = Argon2Params(8, 1, 1)
        val weak = Pvault2.create("pw".toCharArray(), "d", kdf = FAST).bytes
        Pvault2.minimum = Argon2Params.MINIMUM
        assertThrows(VaultFormatException::class.java) { Pvault2.unlockWithPassword(weak, "pw".toCharArray()) }
    }

    @Test
    fun defaultParametersWork() {
        Pvault2.minimum = Argon2Params.MINIMUM
        val t0 = System.nanoTime()
        val c = Pvault2.create("pw".toCharArray(), "d")              // 64 MiB, t=3
        Pvault2.unlockWithPassword(c.bytes, "pw".toCharArray())
        println("default Argon2id create+unlock on this JVM: ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}

class RecoveryKeyTest {
    @Test
    fun formatAndParse() {
        val k = RecoveryKey.generate()
        val s = k.display()
        assertEquals(44, s.length)                                   // 36 chars + 8 dashes
        assertTrue(Regex("^([0-9A-Z]{4}-){8}[0-9A-Z]{4}$").matches(s))
        assertEquals(s, RecoveryKey.parse(s.replace("-", " ").lowercase()).display())
    }

    @Test
    fun ambiguousLettersAccepted() {
        val s = RecoveryKey.generate().display()
        val messy = s.replace('0', 'O').replace('1', 'I')
        assertEquals(s, RecoveryKey.parse(messy).display())
    }

    @Test
    fun typoDetected() {
        val s = RecoveryKey.generate().display()
        val i = 5
        val c = if (s[i] == 'A') 'B' else 'A'
        val typo = s.substring(0, i) + c + s.substring(i + 1)
        assertThrows(IllegalArgumentException::class.java) { RecoveryKey.parse(typo) }
        assertThrows(IllegalArgumentException::class.java) { RecoveryKey.parse("ABCD") }
    }

    @Test
    fun hintPolicy() {
        assertTrue(HintPolicy.check("first pet + year", "Rex1999!".toCharArray()) is HintPolicy.Result.Ok)
        assertTrue(HintPolicy.check("REX19", "Rex1999!".toCharArray()) is HintPolicy.Result.Rejected)
        assertTrue(HintPolicy.check("x".repeat(101), "pw".toCharArray()) is HintPolicy.Result.Rejected)
        assertTrue(HintPolicy.check("", "pw".toCharArray()) is HintPolicy.Result.Ok)
    }
}
