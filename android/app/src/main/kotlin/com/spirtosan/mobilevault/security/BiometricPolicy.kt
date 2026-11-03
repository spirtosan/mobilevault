package com.spirtosan.mobilevault.security

/**
 * When the fingerprint may open the vault instead of the master password (ANDROID_APP_SPEC.md):
 * not after a reboot, not when the password wasn't typed for N days (default 7), not after 5 failed
 * fingerprint attempts, and not when the Keystore key was invalidated by a newly enrolled fingerprint.
 */
object BiometricPolicy {
    const val MAX_FAILURES = 5
    const val DEFAULT_DAYS = 7
    val DAY_CHOICES = listOf(3, 7, 14, 30)
    private const val DAY_MS = 24 * 3_600_000L

    data class State(
        /** A wrapped DEK exists for this vault. */
        val enabled: Boolean,
        /** The key was invalidated (new fingerprint enrolled); cleared when biometrics are set up again. */
        val invalidated: Boolean = false,
        /** Wall-clock ms when the master password was last typed correctly. */
        val lastPasswordAt: Long = 0,
        /** Settings.Global.BOOT_COUNT at that moment. */
        val bootCountAtPassword: Int = -1,
        /** Failed fingerprint attempts since the last successful unlock. */
        val failures: Int = 0,
        val maxDays: Int = DEFAULT_DAYS,
    )

    enum class Need { NONE, NOT_SET_UP, INVALIDATED, REBOOT, DAYS, FAILURES }

    fun check(s: State, bootCount: Int, now: Long): Need = when {
        s.invalidated -> Need.INVALIDATED
        !s.enabled -> Need.NOT_SET_UP
        bootCount != s.bootCountAtPassword -> Need.REBOOT
        now - s.lastPasswordAt >= s.maxDays * DAY_MS || now < s.lastPasswordAt - DAY_MS -> Need.DAYS
        s.failures >= MAX_FAILURES -> Need.FAILURES
        else -> Need.NONE
    }

    /** Why the password is asked for, shown on the unlock screen; null when there is nothing to say. */
    fun reason(need: Need, maxDays: Int): String? = when (need) {
        Need.NONE, Need.NOT_SET_UP -> null
        Need.INVALIDATED -> "Fingerprints changed, enter your master password. Then turn on fingerprint unlock again in Settings."
        Need.REBOOT -> "The phone was restarted: enter your master password."
        Need.DAYS -> "You haven't typed your master password for $maxDays days. Enter it so you don't forget it."
        Need.FAILURES -> "Too many failed fingerprint attempts: enter your master password."
    }
}
