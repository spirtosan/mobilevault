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

    private val _rev = MutableStateFlow(0L)
    /** Revision of the open vault (0 when locked). */
    val rev: StateFlow<Long> = _rev

    private val _lockEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    /**
     * Fires on every lock (idle, background, screen off, manual), even when no vault was open.
     * The value tells whether a vault was open, so screens on the locked side can stay as they are.
     */
    val lockEvents: SharedFlow<Boolean> = _lockEvents

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
        _rev.value = v.rev
        touch()
    }

    /** Wipe the DEK, drop decrypted entries, clear the clipboard. Safe to call from any thread. */
    fun lock() {
        val wasOpen = _payload.value != null
        _payload.value = null
        _rev.value = 0
        clipboard.clear()
        _lockEvents.tryEmit(wasOpen)
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

    /**
     * Save the open vault again with rev ≥ [atLeast], optionally with another payload (restore of an
     * older version). Used when the backup server already has a different file for our rev.
     */
    suspend fun reseal(atLeast: Long, newPayload: VaultPayload? = null) = mutex.withLock {
        val v = vault ?: throw IllegalStateException("vault is locked")
        val before = v.payload
        if (newPayload != null) v.payload = newPayload
        try {
            persist(v, atLeast)
        } catch (e: Exception) {
            v.payload = before
            throw e
        }
        _payload.value = v.payload
    }

    /** Run [block] with the open vault without saving (backup checks with the in-memory DEK). */
    suspend fun <T> read(block: (UnlockedVault) -> T): T? = mutex.withLock { vault?.let(block) }

    private suspend fun persist(v: UnlockedVault, atLeast: Long = 0) {
        val id = deviceId()
        withContext(Dispatchers.IO) { repo.save(v.seal(id, atLeast)) }
        _rev.value = v.rev
    }
}
