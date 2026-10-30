package com.spirtosan.mobilevault.security

import android.os.SystemClock
import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Pvault2

/**
 * Picks Argon2 parameters at vault creation (Task 05 rule): the strongest candidate whose unlock takes
 * at most [BUDGET_MS] on this phone, never below 32 MiB / t=2. All candidates use p=4 lanes.
 * Measured values are in docs/DECISIONS.md (Argon2 benchmark).
 */
object KdfCalibration {
    const val BUDGET_MS = 1000L

    val CANDIDATES = listOf(
        Argon2Params(mKiB = 65536, t = 3, p = 4),
        Argon2Params(mKiB = 65536, t = 2, p = 4),
        Argon2Params(mKiB = 32768, t = 3, p = 4),
        Argon2Params(mKiB = 32768, t = 2, p = 4),
    )

    data class Choice(val params: Argon2Params, val unlockMs: Long)

    /** [measure] returns the ms of one unlock-equivalent run. Stops at the first candidate within budget. */
    fun choose(measure: (Argon2Params) -> Long): Choice {
        var last: Choice? = null
        for (c in CANDIDATES) {
            val ms = measure(c)
            last = Choice(c, ms)
            if (ms <= BUDGET_MS) return last
        }
        return last!!
    }

    /** Real measurement: best of two throw-away vault creations (one Argon2 run each; the first warms up). */
    fun measureOnDevice(p: Argon2Params): Long = (1..2).minOf {
        val t0 = SystemClock.elapsedRealtime()
        val c = Pvault2.create("calibration".toCharArray(), "calibration", kdf = p)
        val ms = SystemClock.elapsedRealtime() - t0
        c.vault.wipe(); c.recoveryKey.wipe()
        ms
    }
}
