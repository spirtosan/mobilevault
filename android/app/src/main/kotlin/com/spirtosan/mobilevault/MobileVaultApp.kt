package com.spirtosan.mobilevault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.spirtosan.mobilevault.backup.BackupException
import com.spirtosan.mobilevault.backup.BackupManager
import com.spirtosan.mobilevault.backup.UploadWorker
import com.spirtosan.mobilevault.backup.DeviceKeys
import com.spirtosan.mobilevault.data.AppSettings
import com.spirtosan.mobilevault.data.Settings
import com.spirtosan.mobilevault.data.VaultRepository
import com.spirtosan.mobilevault.security.BiometricVault
import com.spirtosan.mobilevault.security.Clipboard
import com.spirtosan.mobilevault.security.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-wide objects and the lock rules:
 * - idle for the auto-lock timeout (checked every 5 s, also while in the background),
 * - app in the background longer than the grace period (not while a system file picker is open),
 * - screen off.
 */
class MobileVaultApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repo: VaultRepository private set
    lateinit var appSettings: AppSettings private set
    lateinit var clipboard: Clipboard private set
    lateinit var session: Session private set
    lateinit var settings: StateFlow<Settings> private set
    lateinit var backup: BackupManager private set
    lateinit var biometric: BiometricVault private set

    private var backgroundLock: Job? = null

    override fun onCreate() {
        super.onCreate()
        repo = VaultRepository(filesDir)
        appSettings = AppSettings(this)
        clipboard = Clipboard(this, scope)
        session = Session(repo, clipboard, scope) { appSettings.deviceId() }
        settings = appSettings.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
        backup = BackupManager(filesDir, DeviceKeys(this))
        repo.onSaved = { queueBackup() }
        biometric = BiometricVault(filesDir)

        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = session.lock()
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (session.externalActivityOpen) return
                backgroundLock?.cancel()
                backgroundLock = scope.launch {
                    delay(settings.value.backgroundGraceSeconds * 1000L)
                    session.lock()
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                backgroundLock?.cancel()
                queueBackup()          // app start / app opened: retry, HyperOS may have delayed the job
            }
        })

        scope.launch { backup.status.collect { if (it.needsNewRev) resolveNewRevision() } }

        scope.launch {
            while (isActive) {
                delay(5_000)
                val idleMs = SystemClock.elapsedRealtime() - session.lastInteraction
                if (session.isUnlocked && idleMs >= settings.value.autoLockMinutes * 60_000L) {
                    session.lock()
                }
            }
        }
    }

    /** Queue an upload of the current vault file (if paired). Safe to call often: one job chain only. */
    fun queueBackup() {
        if (backup.isPaired && repo.exists()) UploadWorker.enqueue(this, settings.value.backupWifiOnly)
    }

    private val resolving = Mutex()

    /**
     * The server has a different file for our rev (restore of an older version, another device):
     * while the vault is open, save it again as `max(local, remote latest) + 1` and upload that.
     * Called on unlock and whenever the flag is set. Never overwrites anything on the server.
     */
    suspend fun resolveNewRevision() = resolving.withLock {
        if (!backup.status.value.needsNewRev || !session.isUnlocked) return@withLock
        val t = backup.target() ?: return@withLock
        val vaultId = session.read { it.vaultId } ?: return@withLock
        val remote = try { t.latest(vaultId)?.rev ?: 0L } catch (e: BackupException) { return@withLock }
        session.reseal(maxOf(session.rev.value, remote) + 1)
        backup.updateStatus { it.copy(needsNewRev = false, lastError = null) }
    }
}
