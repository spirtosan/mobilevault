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

/** The recovery key on screen. [created] is the not-yet-saved new vault (create flow), null when regenerating. */
class KitState(val key: RecoveryKey, val vaultId: String, val created: Pvault2.Created?, val challenge: List<Int>) {
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
        viewModelScope.launch { session.lockEvents.collect { onLocked() } }
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

    private fun onLocked() {
        kit?.let { k -> k.created?.vault?.wipe(); k.key.wipe() }
        kit = null
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
        kit = KitState(created.recoveryKey, created.vault.vaultId, created, challenge())
        replace(Screen.EmergencyKit)
    }

    private fun challenge() = (0 until 9).shuffled().take(3).sorted()

    /** The user re-typed the 3 groups: save the new vault (or the new recovery slot) and wipe the key. */
    fun confirmKit(typed: List<String>): Boolean {
        val k = kit ?: return false
        val ok = k.challenge.indices.all { i -> typed[i].trim().equals(k.groups[k.challenge[i]], ignoreCase = true) }
        if (!ok) return false
        work {
            if (k.created != null) {
                withContext(Dispatchers.IO) { repo.save(k.created.bytes) }
                session.open(k.created.vault)
            } else {
                session.withVault(save = true) { }
            }
            k.key.wipe()
            kit = null
            replace(Screen.VaultList)
            message = if (k.created != null) "Vault created" else "New recovery key saved. The old one no longer works."
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
        hint = runCatching { Pvault2.peekHeader(repo.read()).hint }.getOrNull()
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
        val key = try { RecoveryKey.parse(text) } catch (e: IllegalArgumentException) {
            message = "That is not a valid recovery key (check for typos)"; return@work
        }
        try {
            val v = withContext(Dispatchers.Default) { Pvault2.unlockWithRecoveryKey(repo.read(), key) }
            prefs.clearFailures()
            session.open(v)
            replace(Screen.NewPassword(forced = true))
        } catch (e: WrongKeyException) {
            message = "This recovery key does not open this vault"
        } finally { key.wipe() }
    }

    private suspend fun openAndShowList(v: UnlockedVault) {
        session.open(v)
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

    fun regenerateRecoveryKey() = work {
        val (rk, id) = session.withVault(save = false) { it.regenerateRecoveryKey() to it.vaultId }
        kit = KitState(rk, id, null, challenge())
        replace(Screen.EmergencyKit)
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
