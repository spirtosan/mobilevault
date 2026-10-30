package com.spirtosan.mobilevault.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            autoLockMinutes = p[K.autoLock] ?: 5,
            backgroundGraceSeconds = p[K.grace] ?: 0,
            clipboardClearSeconds = p[K.clipboard] ?: 30,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(s: Settings) {
        context.store.edit {
            it[K.autoLock] = s.autoLockMinutes; it[K.grace] = s.backgroundGraceSeconds; it[K.clipboard] = s.clipboardClearSeconds
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
}
