package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.security.BiometricPolicy
import com.spirtosan.mobilevault.security.BiometricPolicy.Need
import com.spirtosan.mobilevault.security.BiometricPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricPolicyTest {
    private val day = 24 * 3_600_000L
    private val t0 = 1_000 * day
    private val ok = State(enabled = true, lastPasswordAt = t0, bootCountAtPassword = 12)

    @Test fun allowedRightAfterThePassword() {
        assertEquals(Need.NONE, BiometricPolicy.check(ok, bootCount = 12, now = t0 + 3_600_000))
        assertNull(BiometricPolicy.reason(Need.NONE, 7))
    }

    @Test fun notSetUp() = assertEquals(Need.NOT_SET_UP, BiometricPolicy.check(ok.copy(enabled = false), 12, t0))

    @Test fun rebootNeedsPassword() {
        assertEquals(Need.REBOOT, BiometricPolicy.check(ok, bootCount = 13, now = t0 + 60_000))
        assertTrue(BiometricPolicy.reason(Need.REBOOT, 7)!!.contains("restarted"))
        // never typed since install / data clear
        assertEquals(Need.REBOOT, BiometricPolicy.check(ok.copy(bootCountAtPassword = -1), 12, t0))
    }

    @Test fun nDays() {
        assertEquals(Need.NONE, BiometricPolicy.check(ok, 12, t0 + 7 * day - 1))
        assertEquals(Need.DAYS, BiometricPolicy.check(ok, 12, t0 + 7 * day))
        assertEquals(Need.NONE, BiometricPolicy.check(ok.copy(maxDays = 14), 12, t0 + 10 * day))
        assertEquals(Need.DAYS, BiometricPolicy.check(ok.copy(maxDays = 3), 12, t0 + 3 * day))
        assertEquals(Need.DAYS, BiometricPolicy.check(ok, 12, t0 - 2 * day))          // clock moved back a lot
        assertTrue(BiometricPolicy.reason(Need.DAYS, 14)!!.contains("14 days"))
        assertEquals(listOf(3, 7, 14, 30), BiometricPolicy.DAY_CHOICES)
        assertEquals(7, BiometricPolicy.DEFAULT_DAYS)
    }

    @Test fun fiveFailures() {
        assertEquals(Need.NONE, BiometricPolicy.check(ok.copy(failures = 4), 12, t0))
        assertEquals(Need.FAILURES, BiometricPolicy.check(ok.copy(failures = 5), 12, t0))
    }

    @Test fun invalidatedWinsAndExplains() {
        val s = ok.copy(enabled = false, invalidated = true)
        assertEquals(Need.INVALIDATED, BiometricPolicy.check(s, 12, t0))
        assertTrue(BiometricPolicy.reason(Need.INVALIDATED, 7)!!.startsWith("Fingerprints changed"))
    }
}
