package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.security.Reminders
import com.spirtosan.mobilevault.security.Reminders.Times
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val d = 24 * 3_600_000L
    private val t0 = 500 * d

    @Test fun practiceEvery30DaysWithoutTyping() {
        val t = Times(start = t0)
        assertFalse(Reminders.practiceDue(t, t0 + 29 * d))
        assertTrue(Reminders.practiceDue(t, t0 + 30 * d))
        assertFalse(Reminders.practiceDue(t.copy(passwordTypedAt = t0 + 20 * d), t0 + 30 * d))   // typed at unlock
        assertFalse(Reminders.practiceDue(t.copy(practiceDismissedAt = t0 + 25 * d), t0 + 40 * d))
        assertTrue(Reminders.practiceDue(t.copy(practiceDismissedAt = t0 + 25 * d), t0 + 55 * d))
        assertFalse(Reminders.practiceDue(Times(), t0 + 999 * d))                                // clock not started
    }

    @Test fun kitYearlyOnlyWithRecoveryKey() {
        val t = Times(start = t0)
        assertFalse(Reminders.kitDue(t, true, t0 + 364 * d))
        assertTrue(Reminders.kitDue(t, true, t0 + 365 * d))
        assertFalse(Reminders.kitDue(t, false, t0 + 999 * d))                                   // no key: no nagging
        assertFalse(Reminders.kitDue(t.copy(kitCheckedAt = t0 + 300 * d), true, t0 + 400 * d))
    }
}
