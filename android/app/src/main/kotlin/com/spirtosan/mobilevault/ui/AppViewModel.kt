package com.spirtosan.mobilevault.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spirtosan.mobilevault.MobileVaultApp
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.HintPolicy
import com.spirtosan.mobilevault.crypto.Pvault1
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.RecoveryKey
import com.spirtosan.mobilevault.crypto.UnlockedVault
import com.spirtosan.mobilevault.crypto.VaultFormatException
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.crypto.WrongKeyException
import com.spirtosan.mobilevault.crypto.wipe
import com.spirtosan.mobilevault.data.Settings
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.security.KdfCalibration
import com.spirtosan.mobilevault.security.PasswordGenerator
import com.spirtosan.mobilevault.security.UnlockThrottle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface Screen {
    data object Loading : Screen
    data object Onboarding : Screen
    data class Create(val imported: VaultPayload?) : Screen
    data object EmergencyKit : Screen
    data object Unlock : Screen
    data object Recovery : Screen
    /** [forced] after a recovery-key unlock: the user must set a new master password. */
    data class NewPassword(val forced: Boolean) : Screen
    data object VaultList : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
    data object Settings : Screen
    data object ImportPassword : Screen
    data class ImportPreview(val count: Int) : Screen
    data object Export : Screen
    data object About : Screen
}

/**
 * The recovery key on screen. The vault holding it is already saved and open, so a lock on the
 * Emergency Kit screen never leaves a printed key without its vault. [newVault]: create flow.
 */
class KitState(val key: RecoveryKey, val vaultId: String, val newVault: Boolean, val challenge: List<Int>) {
    val display: String = key.display()
    val groups: List<String> get() = display.split("-")
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as MobileVaultApp
    private val session = a.session
    private val repo = a.repo
    private val prefs = a.appSettings

    var screen by mutableStateOf<Screen>(Screen.Loading); private set
    private val backStack = ArrayDeque<Screen>()

    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    // Unlock screen
    var unlockError by mutableStateOf<String?>(null); private set
    var showHintButton by mutableStateOf(false); private set
    var hint by mutableStateOf<String?>(null); private set
    var waitSeconds by mutableIntStateOf(0); private set
    private var waitJob: Job? = null
    /** From the (unauthenticated) header, shown on the recovery screen to compare with the Emergency Kit. */
    var vaultId by mutableStateOf<String?>(null); private set
    var recoveryError by mutableStateOf<String?>(null)

    /** The Emergency Kit of the open vault was never confirmed (see [AppSettings.kitPending]). */
    var kitPending by mutableStateOf(false); private set

    // Create / new password
    var calibration by mutableStateOf<KdfCalibration.Choice?>(null); private set
    var kit by mutableStateOf<KitState?>(null); private set

    // Import / export
    private var importBytes: ByteArray? = null
    private var imported: VaultPayload? = null

    val payload get() = session.payload
    val settings get() = a.settings
    val generator = PasswordGenerator({ words })
    private val words: List<String> by lazy {
        app.assets.open("eff_short_wordlist_1.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    }

    init {
        screen = if (session.isUnlocked) Screen.VaultList else if (repo.exists()) Screen.Unlock else Screen.Onboarding
        viewModelScope.launch { session.lockEvents.collect { onLocked(it) } }
        if (screen == Screen.Unlock) prepareUnlock()
    }

    // ── navigation ────────────────────────────────────────────────────────────
    fun go(s: Screen) { backStack.addLast(screen); screen = s; onEnter(s) }

    private fun replace(s: Screen) { backStack.clear(); screen = s; onEnter(s) }

    /** Returns false when there is nowhere to go back to (the activity then finishes). */
    fun back(): Boolean {
        when (screen) {
            Screen.EmergencyKit -> return true                       // must be completed
            is Screen.NewPassword -> if ((screen as Screen.NewPassword).forced) { session.lock(); return true }
            else -> {}
        }
        val prev = backStack.removeLastOrNull() ?: return false
        screen = prev
        return true
    }

    private fun onEnter(s: Screen) {
        when (s) {
            is Screen.Create, is Screen.NewPassword -> calibrate()
            Screen.Unlock -> prepareUnlock()
            else -> {}
        }
    }

    /**
     * Lock events also fire when nothing is open (screen off on the Unlock or Recovery screen...).
     * Then the screen and what was typed stay; only open or decrypted data forces a reset.
     */
    private fun onLocked(wasOpen: Boolean) {
        val pending = kit != null || importBytes != null || imported != null || (screen as? Screen.Create)?.imported != null
        if (!wasOpen && !pending) return
        kit?.key?.wipe()
        kit = null
        kitPending = false
        importBytes?.wipe(); importBytes = null; imported = null
        busy = false
        replace(if (repo.exists()) Screen.Unlock else Screen.Onboarding)
    }

    fun lockNow() = session.lock()

    /** Around system file pickers: leaving the app for them must not trigger the background lock. */
    fun externalStarted() { session.externalActivityOpen = true }
    fun externalDone() { session.externalActivityOpen = false; session.touch() }
    fun touch() = session.touch()

    // ── create ────────────────────────────────────────────────────────────────
    private fun calibrate() {
        if (calibration != null) return
        viewModelScope.launch {
            calibration = withContext(Dispatchers.Default) { KdfCalibration.choose(KdfCalibration::measureOnDevice) }
        }
    }

    fun hintProblem(hint: String, password: CharArray): String? =
        (HintPolicy.check(hint, password) as? HintPolicy.Result.Rejected)?.reason

    fun createVault(password: CharArray, hint: String, importedPayload: VaultPayload?) = work {
        val kdf = (calibration ?: withContext(Dispatchers.Default) {
            KdfCalibration.choose(KdfCalibration::measureOnDevice)
        }.also { calibration = it }).params
        val device = prefs.deviceId()
        val created = withContext(Dispatchers.Default) {
            Pvault2.create(password, device, hint.ifBlank { null }, importedPayload ?: VaultPayload(), kdf)
        }
        password.wipe()
        // Save before the key is shown: the user may save or print the Emergency Kit and then get
        // locked out of this screen (screen off, leaving the app). The key must still open the vault.
        withContext(Dispatchers.IO) { repo.save(created.bytes) }
        prefs.setKitPending(true)
        session.open(created.vault)
        showKit(created.recoveryKey, created.vault.vaultId, newVault = true)
    }

    private fun showKit(key: RecoveryKey, vaultId: String, newVault: Boolean) {
        kitPending = true
        kit = KitState(key, vaultId, newVault, challenge())
        replace(Screen.EmergencyKit)
    }

    private fun challenge() = (0 until 9).shuffled().take(3).sorted()

    /** The user re-typed the 3 groups: the key is written down, wipe it. */
    fun confirmKit(typed: List<String>): Boolean {
        val k = kit ?: return false
        val ok = k.challenge.indices.all { i -> typed[i].trim().equals(k.groups[k.challenge[i]], ignoreCase = true) }
        if (!ok) return false
        work {
            prefs.setKitPending(false)
            kitPending = false
            k.key.wipe()
            kit = null
            replace(Screen.VaultList)
            message = if (k.newVault) "Vault created" else "New recovery key saved. The old one no longer works."
        }
        return true
    }

    fun writeKitPdf(uri: Uri) = work {
        val k = kit ?: return@work
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                EmergencyKitPdf.write(it, k.display, k.vaultId, LocalDate.now().toString())
            }
        }
        message = "Emergency Kit saved"
    }

    // ── unlock ────────────────────────────────────────────────────────────────
    private fun prepareUnlock() {
        unlockError = null
        recoveryError = null
        val header = runCatching { Pvault2.peekHeader(repo.read()) }.getOrNull()
        hint = header?.hint
        vaultId = header?.vaultId
        viewModelScope.launch {
            val (n, _) = prefs.failures()
            showHintButton = n > 0 && hint != null
            startWaitCountdown()
        }
    }

    private suspend fun startWaitCountdown() {
        val (n, at) = prefs.failures()
        val wallNow = System.currentTimeMillis()
        val remaining = UnlockThrottle.remainingMs(n, at, wallNow)
        waitJob?.cancel()
        if (remaining <= 0) { waitSeconds = 0; return }
        val end = SystemClock.elapsedRealtime() + remaining
        waitJob = viewModelScope.launch {
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                waitSeconds = ((left + 999) / 1000).toInt().coerceAtLeast(0)
                if (left <= 0) break
                delay(250)
            }
        }
    }

    fun unlock(password: CharArray) {
        if (waitSeconds > 0) return
        work {
            try {
                val v = withContext(Dispatchers.Default) { Pvault2.unlockWithPassword(repo.read(), password) }
                prefs.clearFailures()
                openAndShowList(v)
            } catch (e: WrongKeyException) {
                prefs.recordFailure(System.currentTimeMillis())
                val n = prefs.failures().first
                unlockError = "Wrong password" + if (n >= UnlockThrottle.FREE_ATTEMPTS) " ($n failed attempts)" else ""
                showHintButton = hint != null
                startWaitCountdown()
            } finally { password.wipe() }
        }
    }

    fun unlockWithRecoveryKey(text: String) = work {
        recoveryError = null
        val key = try { RecoveryKey.parse(text) } catch (e: IllegalArgumentException) {
            recoveryError = e.message ?: "That is not a valid recovery key"; return@work
        }
        try {
            val v = withContext(Dispatchers.Default) { Pvault2.unlockWithRecoveryKey(repo.read(), key) }
            prefs.clearFailures()
            prefs.setKitPending(false)                   // the user evidently has the key
            session.open(v)
            replace(Screen.NewPassword(forced = true))
        } catch (e: WrongKeyException) {
            recoveryError = "This recovery key does not open this vault. Check that the Vault ID on your " +
                "Emergency Kit matches the one above. A kit for an older vault or an old key (replaced in Settings) won't work."
        } finally { key.wipe() }
    }

    private suspend fun openAndShowList(v: UnlockedVault) {
        session.open(v)
        kitPending = prefs.kitPending()
        replace(Screen.VaultList)
    }

    // ── password & recovery key changes ───────────────────────────────────────
    /** [current] is null after a recovery-key unlock (forced flow). */
    fun setNewPassword(current: CharArray?, newPassword: CharArray, hint: String) = work {
        val kdf = (calibration ?: withContext(Dispatchers.Default) {
            KdfCalibration.choose(KdfCalibration::measureOnDevice)
        }.also { calibration = it }).params
        try {
            if (current != null) {
                val ok = withContext(Dispatchers.Default) { session.withVault(false) { it.verifyPassword(current) } }
                if (!ok) { message = "Current password is wrong"; return@work }
            }
            withContext(Dispatchers.Default) {
                session.withVault(save = true) { it.changePassword(newPassword, hint.ifBlank { null }, kdf) }
            }
            replace(Screen.VaultList)
            message = "Master password changed"
        } finally { current?.wipe(); newPassword.wipe() }
    }

    /** Saved at once (like create): a kit saved as PDF must match the file even if the app locks before confirming. */
    fun regenerateRecoveryKey() = work {
        val (rk, id) = session.withVault(save = true) { it.regenerateRecoveryKey() to it.vaultId }
        prefs.setKitPending(true)
        showKit(rk, id, newVault = false)
    }

    // ── entries ───────────────────────────────────────────────────────────────
    fun saveEntry(e: Entry) = work {
        session.update { VaultEdits.upsert(it, e) }
        back()
        message = "Saved"
    }

    fun deleteEntry(id: String) = work {
        session.update { VaultEdits.delete(it, id) }
        replace(Screen.VaultList)
        message = "Deleted"
    }

    fun toggleFavourite(e: Entry) = work { session.update { VaultEdits.upsert(it, e.copy(favourite = !e.favourite)) } }

    fun copy(label: String, text: String) {
        a.clipboard.copySecret(label, text, settings.value.clipboardClearSeconds)
        val s = settings.value.clipboardClearSeconds
        message = "$label copied" + if (s > 0) ", clears in $s s" else ""
    }

    fun updateSettings(s: Settings) = viewModelScope.launch { prefs.update(s) }

    // ── import / export (desktop PVAULT1) ─────────────────────────────────────
    fun importPicked(uri: Uri) = work {
        importBytes = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        go(Screen.ImportPassword)
    }

    fun importDecrypt(password: CharArray) = work {
        val raw = importBytes ?: return@work
        try {
            val p = withContext(Dispatchers.Default) { Pvault1.decrypt(password, raw) }
            imported = p
            screen = Screen.ImportPreview(p.activeEntries.size)
        } catch (e: WrongKeyException) {
            message = "Wrong password for this file"
        } catch (e: VaultFormatException) {
            message = "Not a desktop vault file: ${e.message}"
        } finally { password.wipe() }
    }

    fun importAsNewVault() {
        val p = imported ?: return
        importBytes = null; imported = null
        replace(Screen.Create(p))
    }

    fun importMerge() = work {
        val p = imported ?: return@work
        var added = 0; var skipped = 0
        session.update { cur -> VaultEdits.mergeImport(cur, p.entries).also { added = it.added; skipped = it.skipped }.payload }
        importBytes = null; imported = null
        replace(Screen.VaultList)
        message = "Imported $added entries" + if (skipped > 0) ", skipped $skipped duplicates" else ""
    }

    fun exportTo(uri: Uri, password: CharArray) = work {
        try {
            val bytes = withContext(Dispatchers.Default) {
                Pvault1.encrypt(password, session.payload.value ?: error("locked"))
            }
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            }
            back()
            message = "Exported for the desktop app"
        } finally { password.wipe() }
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { block() } catch (e: Exception) {
                message = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally { busy = false }
        }
    }
}
