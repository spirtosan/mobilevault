package com.spirtosan.mobilevault.security

import java.security.SecureRandom
import kotlin.math.log2
import kotlin.math.min

/**
 * Password strength, an improved port of the desktop `score_password`: instead of counting rules it
 * estimates entropy (length × log2(character pool)), discounts repeats and sequences ("aaa", "abc", "321"),
 * and caps well-known passwords at "Very weak".
 */
object PasswordStrength {
    val LABELS = listOf("Very weak", "Weak", "Fair", "Strong", "Very strong")
    private val COMMON = setOf(
        "password", "passw0rd", "123456", "12345678", "123456789", "1234567890", "qwerty", "qwertz", "azerty",
        "letmein", "iloveyou", "admin", "welcome", "monkey", "dragon", "football", "baseball", "abc123",
        "111111", "000000", "sunshine", "princess", "master", "shadow", "trustno1", "superman", "michael",
    )

    data class Score(val level: Int, val bits: Int) { val label get() = LABELS[level] }

    fun score(pw: CharArray): Score {
        if (pw.isEmpty()) return Score(0, 0)
        var pool = 0
        if (pw.any { it in 'a'..'z' }) pool += 26
        if (pw.any { it in 'A'..'Z' }) pool += 26
        if (pw.any { it in '0'..'9' }) pool += 10
        if (pw.any { it.code in 32..126 && !it.isLetterOrDigit() }) pool += 33
        if (pw.any { it.code > 126 }) pool += 100
        var effective = 1.0
        for (i in 1 until pw.size) {
            val d = pw[i].code - pw[i - 1].code
            effective += if (d == 0 || d == 1 || d == -1) 0.25 else 1.0
        }
        val bits = (effective * log2(pool.toDouble())).toInt()
        val lower = String(pw).lowercase()
        if (COMMON.any { lower == it || (it.length >= 6 && lower.contains(it)) }) return Score(0, min(bits, 10))
        val level = when {
            bits < 28 -> 0
            bits < 40 -> 1
            bits < 60 -> 2
            bits < 80 -> 3
            else -> 4
        }
        return Score(level, bits)
    }
}

/** Random passwords and passphrases (SecureRandom). */
class PasswordGenerator(private val words: () -> List<String>, private val rng: SecureRandom = SecureRandom()) {
    data class Options(
        val length: Int = 20,
        val lower: Boolean = true,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val passphrase: Boolean = false,
        val words: Int = 5,
        val separator: String = "-",
    )

    fun generate(o: Options): String = if (o.passphrase) passphrase(o) else password(o)

    private fun password(o: Options): String {
        require(o.length in MIN_LENGTH..MAX_LENGTH)
        val sets = buildList {
            if (o.lower) add(LOWER); if (o.upper) add(UPPER); if (o.digits) add(DIGITS); if (o.symbols) add(SYMBOLS)
        }.ifEmpty { listOf(LOWER) }
        val all = sets.joinToString("")
        val out = CharArray(o.length)
        // One character from each chosen set, the rest from all of them, then shuffle.
        sets.forEachIndexed { i, s -> out[i] = s[rng.nextInt(s.length)] }
        for (i in sets.size until o.length) out[i] = all[rng.nextInt(all.length)]
        for (i in out.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return String(out).also { out.fill('\u0000') }
    }

    private fun passphrase(o: Options): String {
        val list = words()
        require(list.size >= 1000 && o.words in MIN_WORDS..MAX_WORDS)
        return (1..o.words).joinToString(o.separator) { list[rng.nextInt(list.size)] }
    }

    companion object {
        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 64
        const val MIN_WORDS = 4
        const val MAX_WORDS = 10
        const val LOWER = "abcdefghijklmnopqrstuvwxyz"
        const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val DIGITS = "0123456789"
        const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/"
    }
}

/** Delay before the next unlock attempt: none for the first 5 failures, then 30 s doubling up to 15 min. */
object UnlockThrottle {
    const val FREE_ATTEMPTS = 5
    private const val BASE_MS = 30_000L
    private const val MAX_MS = 15 * 60_000L

    fun delayMs(failures: Int): Long =
        if (failures < FREE_ATTEMPTS) 0 else min(MAX_MS, BASE_MS shl min(failures - FREE_ATTEMPTS, 10))

    fun remainingMs(failures: Int, lastFailureMs: Long, nowMs: Long): Long =
        (lastFailureMs + delayMs(failures) - nowMs).coerceAtLeast(0)
}
