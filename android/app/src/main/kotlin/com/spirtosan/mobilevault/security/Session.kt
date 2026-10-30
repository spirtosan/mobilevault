package com.spirtosan.mobilevault.security

import android.os.SystemClock
import com.spirtosan.mobilevault.crypto.UnlockedVault
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single open vault. All access to [UnlockedVault] goes through [mutex] (it is not thread-safe),
 * and every change is sealed (rev+1) and saved atomically before the UI sees it.
 */
class Session(
    private val repo: VaultRepository,
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
    private val deviceId: suspend () -> String,
) {
    private val mutex = Mutex()
    private var vault: UnlockedVault? = null

    private val _payload = MutableStateFlow<VaultPayload?>(null)
    /** Decrypted entries while unlocked, null when locked. */
    val payload: StateFlow<VaultPayload?> = _payload

    private val _lockEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Fires on every lock (idle, background, screen off, manual), even when no vault was open. */
    val lockEvents: SharedFlow<Unit> = _lockEvents

    @Volatile var lastInteraction = SystemClock.elapsedRealtime()
        private set

    /** Set while a system file picker is open, so leaving the app for it does not lock. */
    @Volatile var externalActivityOpen = false

    val isUnlocked get() = _payload.value != null

    fun touch() { lastInteraction = SystemClock.elapsedRealtime() }

    suspend fun open(v: UnlockedVault) = mutex.withLock {
        vault?.wipe()
        vault = v
        _payload.value = v.payload
        touch()
    }

    /** Wipe the DEK, drop decrypted entries, clear the clipboard. Safe to call from any thread. */
    fun lock() {
        _payload.value = null
        clipboard.clear()
        _lockEvents.tryEmit(Unit)
        scope.launch { mutex.withLock { vault?.wipe(); vault = null } }
    }

    /** Apply [change] to the payload, seal and save. On failure nothing is shown as saved. */
    suspend fun update(change: (VaultPayload) -> VaultPayload) = mutex.withLock {
        val v = vault ?: throw IllegalStateException("vault is locked")
        val before = v.payload
        v.payload = change(before)
        try {
            persist(v)
        } catch (e: Exception) {
            v.payload = before
            throw e
        }
        _payload.value = v.payload
    }

    /** Run [block] on the open vault (change password, new recovery key...), then seal and save if [save]. */
    suspend fun <T> withVault(save: Boolean, block: (UnlockedVault) -> T): T = mutex.withLock {
        val v = vault ?: throw IllegalStateException("vault is locked")
        val r = block(v)
        if (save) persist(v)
        r
    }

    private suspend fun persist(v: UnlockedVault) {
        val id = deviceId()
        withContext(Dispatchers.IO) { repo.save(v.seal(id)) }
    }
}
