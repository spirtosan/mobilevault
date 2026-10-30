package com.spirtosan.mobilevault.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The fast Argon2id must be byte-identical to RFC 9106, BouncyCastle and argon2-cffi. */
class Argon2Test {
    private fun res(name: String): ByteArray =
        Argon2Test::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun bc(pw: ByteArray, salt: ByteArray, m: Int, t: Int, p: Int, len: Int,
                   secret: ByteArray? = null, ad: ByteArray? = null): ByteArray {
        val b = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(m).withIterations(t).withParallelism(p).withSalt(salt)
        if (secret != null) b.withSecret(secret)
        if (ad != null) b.withAdditional(ad)
        return ByteArray(len).also { Argon2BytesGenerator().apply { init(b.build()) }.generateBytes(pw, it) }
    }

    @Test
    fun rfc9106TestVector() {
        // RFC 9106 §5.3: Argon2id, m=32 KiB, t=3, p=4, 32-byte tag, with secret and associated data.
        val tag = Argon2id.hash(
            password = ByteArray(32) { 1 }, salt = ByteArray(16) { 2 }, mKiB = 32, t = 3, p = 4, outLen = 32,
            secret = ByteArray(8) { 3 }, ad = ByteArray(12) { 4 },
        )
        assertArrayEquals(hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"), tag)
    }

    @Test
    fun matchesBouncyCastle() {
        val rnd = Random(42)
        var n = 0
        for (p in listOf(1, 2, 4)) for ((m, t) in listOf(32 to 1, 64 to 3, 300 to 2, 1024 to 1, 4096 to 3, 8192 to 2)) {
            if (m < 8 * p) continue
            for (len in listOf(32, 64, 77)) {
                val pw = rnd.nextBytes(rnd.nextInt(0, 64))
                val salt = rnd.nextBytes(16)
                assertArrayEquals(bc(pw, salt, m, t, p, len), Argon2id.hash(pw, salt, m, t, p, len), "m=$m t=$t p=$p len=$len")
                n++
            }
        }
        // secret + associated data path
        val pw = rnd.nextBytes(20); val salt = rnd.nextBytes(16); val k = rnd.nextBytes(8); val x = rnd.nextBytes(12)
        assertArrayEquals(bc(pw, salt, 256, 2, 2, 32, k, x), Argon2id.hash(pw, salt, 256, 2, 2, 32, k, x))
        assertEquals(54, n)
    }

    @Test
    fun matchesArgon2Cffi() {
        val cases = Json.parseToJsonElement(res("argon2-cffi-vectors.json").toString(Charsets.UTF_8))
            .jsonObject["cases"]!!.jsonArray
        assertTrue(cases.map { it.jsonObject["p"]!!.jsonPrimitive.int }.toSet() == setOf(1, 2, 4))
        for (c in cases) {
            val o = c.jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            fun i(k: String) = o[k]!!.jsonPrimitive.int
            assertArrayEquals(hex(s("tag")), Argon2id.hash(hex(s("pw")), hex(s("salt")), i("m"), i("t"), i("p"), i("len")), o.toString())
        }
    }

    @Test
    fun productionSizesMatchBouncyCastle() {
        val pw = "Café ☕ correct horse".toByteArray(); val salt = Random(7).nextBytes(16)
        for (p in listOf(1, 4)) assertArrayEquals(bc(pw, salt, 32768, 2, p, 32), Argon2id.hash(pw, salt, 32768, 2, p, 32), "p=$p")
    }

    @Test
    fun pythonP4VaultOpens() {
        Pvault2.minimum = Argon2Params(8, 1, 1)
        try {
            val meta = Json.parseToJsonElement(res("sample-pvault2-p4.json").toString(Charsets.UTF_8)).jsonObject
            val raw = res("sample-pvault2-p4.bin")
            val v = Pvault2.unlockWithPassword(raw, meta["password"]!!.jsonPrimitive.content.toCharArray())
            assertEquals("Lanes", v.payload.entries.single().service)
            assertEquals(4, Pvault2.peekHeader(raw).slot("password")!!.kdf["p"]!!.jsonPrimitive.int)
            val viaRk = Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(meta["recovery_key"]!!.jsonPrimitive.content))
            assertEquals(v.payload, viaRk.payload)
        } finally { Pvault2.minimum = Argon2Params.MINIMUM }
    }

    @Test
    fun newVaultsUseFourLanes() {
        assertEquals(4, Argon2Params().p)
        Pvault2.minimum = Argon2Params(8, 1, 1)
        try {
            val c = Pvault2.create("pw".toCharArray(), "d", kdf = Argon2Params(1024, 1))
            assertEquals(4, Pvault2.peekHeader(c.bytes).slot("password")!!.kdf["p"]!!.jsonPrimitive.int)
        } finally { Pvault2.minimum = Argon2Params.MINIMUM }
    }
}
