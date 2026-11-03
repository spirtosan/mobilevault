package com.spirtosan.mobilevault.security

/**
 * Master password reminders (ANDROID_APP_SPEC.md "Master password reminder"):
 * - "Practice your master password" every 30 days, counted from the last time the password was typed
 *   (unlock, practice, change) or the card was dismissed. Without fingerprint unlock the password is
 *   typed at every unlock, so the card only shows up when it is really rarely used.
 * - "Is your Emergency Kit still safe?" once a year, only if the vault has a recovery key.
 * [start] is when reminders were first seen on this install, so a fresh install doesn't nag at once.
 */
object Reminders {
    const val PRACTICE_DAYS = 30
    const val KIT_DAYS = 365
    private const val DAY_MS = 24 * 3_600_000L

    data class Times(
        val start: Long = 0,
        val passwordTypedAt: Long = 0,
        val practiceDismissedAt: Long = 0,
        val kitCheckedAt: Long = 0,
        /** Debug builds only: added to the clock to test the reminders. */
        val debugOffsetMs: Long = 0,
    )

    fun practiceDue(t: Times, now: Long): Boolean =
        t.start > 0 && now - maxOf(t.start, t.passwordTypedAt, t.practiceDismissedAt) >= PRACTICE_DAYS * DAY_MS

    fun kitDue(t: Times, hasRecoveryKey: Boolean, now: Long): Boolean =
        hasRecoveryKey && t.start > 0 && now - maxOf(t.start, t.kitCheckedAt) >= KIT_DAYS * DAY_MS
}
