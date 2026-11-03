package com.spirtosan.mobilevault.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spirtosan.mobilevault.security.BiometricPolicy
import com.spirtosan.mobilevault.security.Reminders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.store by preferencesDataStore("settings")

/** Non-secret preferences only. Nothing here can help decrypt the vault. */
data class Settings(
    val autoLockMinutes: Int = 5,
    val backgroundGraceSeconds: Int = 0,
    val clipboardClearSeconds: Int = 30,    // 0 = never
    /** Upload backups only on unmetered networks (Wi-Fi). */
    val backupWifiOnly: Boolean = false,
) {
    companion object {
        val AUTO_LOCK_CHOICES = listOf(1, 2, 5, 10, 30)
        val GRACE_CHOICES = listOf(0, 30, 60)
        val CLIPBOARD_CHOICES = listOf(10, 30, 60, 0)
    }
}

class AppSettings(private val context: Context) {
    private object K {
        val autoLock = intPreferencesKey("auto_lock_min")
        val grace = intPreferencesKey("bg_grace_s")
        val clipboard = intPreferencesKey("clipboard_s")
        val deviceId = stringPreferencesKey("device_id")
        val failCount = intPreferencesKey("unlock_fail_count")
        val failAt = longPreferencesKey("unlock_fail_at")
        val kitPending = booleanPreferencesKey("kit_pending")
        val wifiOnly = booleanPreferencesKey("backup_wifi_only")
        val testRestoreDismissed = longPreferencesKey("test_restore_dismissed_at")
        val bioDays = intPreferencesKey("bio_max_days")
        val pwTypedAt = longPreferencesKey("password_typed_at")
        val pwTypedBoot = intPreferencesKey("password_typed_boot")
        val bioFailures = intPreferencesKey("bio_failures")
        val bioInvalidated = booleanPreferencesKey("bio_invalidated")
        val reminderStart = longPreferencesKey("reminder_start")
        val practiceDismissed = longPreferencesKey("practice_dismissed_at")
        val kitChecked = longPreferencesKey("kit_checked_at")
        val debugClock = longPreferencesKey("debug_clock_offset_ms")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            autoLockMinutes = p[K.autoLock] ?: 5,
            backgroundGraceSeconds = p[K.grace] ?: 0,
            clipboardClearSeconds = p[K.clipboard] ?: 30,
            backupWifiOnly = p[K.wifiOnly] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(s: Settings) {
        context.store.edit {
            it[K.autoLock] = s.autoLockMinutes; it[K.grace] = s.backgroundGraceSeconds; it[K.clipboard] = s.clipboardClearSeconds
            it[K.wifiOnly] = s.backupWifiOnly
        }
    }

    /** Random per-install id written into the vault header (`device`); not tied to the hardware. */
    suspend fun deviceId(): String {
        context.store.data.first()[K.deviceId]?.let { return it }
        val id = "android-" + UUID.randomUUID().toString().take(8)
        context.store.edit { it[K.deviceId] = id }
        return id
    }

    /** Failed unlock attempts survive app restarts, so killing the app does not reset the delay. */
    suspend fun failures(): Pair<Int, Long> = context.store.data.first().let { (it[K.failCount] ?: 0) to (it[K.failAt] ?: 0L) }

    suspend fun recordFailure(nowMs: Long) {
        context.store.edit { it[K.failCount] = (it[K.failCount] ?: 0) + 1; it[K.failAt] = nowMs }
    }

    suspend fun clearFailures() {
        context.store.edit { it.remove(K.failCount); it.remove(K.failAt) }
    }

    /**
     * A recovery key was saved into the vault but the user has not yet confirmed the Emergency Kit
     * (the app was locked on that screen). The vault then reminds them to create a new key.
     */
    suspend fun kitPending(): Boolean = context.store.data.first()[K.kitPending] ?: false

    suspend fun setKitPending(pending: Boolean) {
        context.store.edit { if (pending) it[K.kitPending] = true else it.remove(K.kitPending) }
    }

    /** "Test your backup" card dismissed at (wall-clock ms): it comes back 30 days later. */
    val testRestoreDismissedAt: Flow<Long> = context.store.data.map { it[K.testRestoreDismissed] ?: 0L }

    suspend fun setTestRestoreDismissed(ms: Long) { context.store.edit { it[K.testRestoreDismissed] = ms } }

    // ── fingerprint unlock (BiometricPolicy) ─────────────────────────────────────
    suspend fun biometricState(enabled: Boolean): BiometricPolicy.State = context.store.data.first().let {
        BiometricPolicy.State(
            enabled = enabled, invalidated = it[K.bioInvalidated] ?: false,
            lastPasswordAt = it[K.pwTypedAt] ?: 0L, bootCountAtPassword = it[K.pwTypedBoot] ?: -1,
            failures = it[K.bioFailures] ?: 0, maxDays = it[K.bioDays] ?: BiometricPolicy.DEFAULT_DAYS,
        )
    }

    val biometricDays: Flow<Int> = context.store.data.map { it[K.bioDays] ?: BiometricPolicy.DEFAULT_DAYS }

    suspend fun setBiometricDays(days: Int) { context.store.edit { it[K.bioDays] = days } }

    /** The master password was typed correctly (unlock, change, practice, turning on fingerprint unlock). */
    suspend fun recordPasswordTyped(nowMs: Long, bootCount: Int) {
        context.store.edit { it[K.pwTypedAt] = nowMs; it[K.pwTypedBoot] = bootCount; it.remove(K.bioFailures) }
    }

    suspend fun setBiometricFailures(n: Int) { context.store.edit { if (n == 0) it.remove(K.bioFailures) else it[K.bioFailures] = n } }

    suspend fun addBiometricFailure() { context.store.edit { it[K.bioFailures] = (it[K.bioFailures] ?: 0) + 1 } }

    suspend fun setBiometricInvalidated(v: Boolean) { context.store.edit { if (v) it[K.bioInvalidated] = true else it.remove(K.bioInvalidated) } }

    // ── password reminders (Reminders) ──────────────────────────────────────────
    val reminderTimes: Flow<Reminders.Times> = context.store.data.map {
        Reminders.Times(
            start = it[K.reminderStart] ?: 0L, passwordTypedAt = it[K.pwTypedAt] ?: 0L,
            practiceDismissedAt = it[K.practiceDismissed] ?: 0L, kitCheckedAt = it[K.kitChecked] ?: 0L,
            debugOffsetMs = it[K.debugClock] ?: 0L,
        )
    }

    /** First unlock with this version: the reminder clocks start now (no card right after an update). */
    suspend fun startRemindersIfNeeded(nowMs: Long) { context.store.edit { if (it[K.reminderStart] == null) it[K.reminderStart] = nowMs } }

    suspend fun setPracticeDismissed(ms: Long) { context.store.edit { it[K.practiceDismissed] = ms } }

    suspend fun setKitChecked(ms: Long) { context.store.edit { it[K.kitChecked] = ms } }

    /** Debug builds only (the UI is hidden in release, and the offset is ignored there). */
    suspend fun setDebugClockOffset(ms: Long) { context.store.edit { if (ms == 0L) it.remove(K.debugClock) else it[K.debugClock] = ms } }
}
