package com.spirtosan.mobilevault.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings.Global
import javax.crypto.Cipher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spirtosan.mobilevault.BuildConfig
import com.spirtosan.mobilevault.MobileVaultApp
import com.spirtosan.mobilevault.backup.BackupException
import com.spirtosan.mobilevault.backup.HttpBackupTarget
import com.spirtosan.mobilevault.backup.RemoteRev
import com.spirtosan.mobilevault.backup.RemoteVault
import com.spirtosan.mobilevault.backup.Restore
import com.spirtosan.mobilevault.backup.PairingPayload
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.HintPolicy
import com.spirtosan.mobilevault.crypto.Pvault1
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.RecoveryKey
import com.spirtosan.mobilevault.crypto.UnlockedVault
import com.spirtosan.mobilevault.crypto.VaultFormatException
import com.spirtosan.mobilevault.crypto.VaultHeader
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.crypto.WrongKeyException
import com.spirtosan.mobilevault.crypto.wipe
import com.spirtosan.mobilevault.data.Settings
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.security.BiometricPolicy
import com.spirtosan.mobilevault.security.BiometricVault
import com.spirtosan.mobilevault.security.KdfCalibration
import com.spirtosan.mobilevault.security.KeyChanges
import com.spirtosan.mobilevault.security.PasswordGenerator
import com.spirtosan.mobilevault.security.Reminders
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
    /**
     * [forced] after a recovery-key unlock: the user must set a new master password.
     * [withoutCurrent]: from the practice card after a wrong answer, while the vault is still open.
     */
    data class NewPassword(val forced: Boolean, val withoutCurrent: Boolean = false) : Screen
    data object VaultList : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
    data object Settings : Screen
    data object ImportPassword : Screen
    data class ImportPreview(val count: Int) : Screen
    data object Export : Screen
    data object About : Screen
    data object Backup : Screen
    /** Pair with the server; [purpose] decides where the flow continues afterwards. */
    data class Pairing(val purpose: PairPurpose) : Screen
    /** Create flow, step 3: connect to the backup server or skip. */
    data object BackupStep : Screen
    data object RestoreStart : Screen
    data object RestoreList : Screen
    data object RestoreUnlock : Screen
    data object History : Screen
}

enum class PairPurpose { SETTINGS, RESTORE, CREATE }

/**
 * The recovery key on screen. The vault holding it is already saved and open, so a lock on the
 * Emergency Kit screen never leaves a printed key without its vault. [newVault]: create flow.
 */
class KitState(val key: RecoveryKey, val vaultId: String, val newVault: Boolean, val challenge: List<Int>, val doneText: String = "Vault created") {
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
    /** The current [message] reports a problem (red snackbar accent instead of green). */
    var messageError by mutableStateOf(false); private set

    fun fail(text: String) { messageError = true; message = text }

    /** Called by the UI once the snackbar for [message] is shown. */
    fun messageShown() { message = null; messageError = false }

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

    /** The vault has a recovery slot: from the header before unlock, from the open vault after. */
    var hasRecoveryKey by mutableStateOf(false); private set

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
            Screen.RestoreStart -> loadRemoteVaults()
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

    /** [withRecoveryKey] false: the user chose "Skip" on the recovery-key step. */
    fun createVault(password: CharArray, hint: String, importedPayload: VaultPayload?, withRecoveryKey: Boolean) = work {
        if (repo.exists()) {                          // code review F4: never replace an existing vault
            password.wipe()
            fail("This phone already has a vault. A new vault can only be created when there is none.")
            return@work
        }
        val kdf = (calibration ?: withContext(Dispatchers.Default) {
            KdfCalibration.choose(KdfCalibration::measureOnDevice)
        }.also { calibration = it }).params
        val device = prefs.deviceId()
        val created = try {
            withContext(Dispatchers.Default) {
                Pvault2.create(password, device, hint.ifBlank { null }, importedPayload ?: VaultPayload(), kdf, withRecoveryKey)
            }
        } finally { password.wipe() }
        // Save before the key is shown: the user may save or print the Emergency Kit and then get
        // locked out of this screen (screen off, leaving the app). The key must still open the vault.
        withContext(Dispatchers.IO) { repo.save(created.bytes) }
        disableBiometric(quiet = true)                // a new vault has a new DEK
        prefs.setKitPending(created.recoveryKey != null)
        session.open(created.vault)
        hasRecoveryKey = created.recoveryKey != null
        val rk = created.recoveryKey
        if (rk != null) showKit(rk, created.vault.vaultId, newVault = true)
        else { kitPending = false; replace(afterCreate()); message = "Vault created without a recovery key" }
    }

    /** After a new vault: offer the backup server (step 3) unless this phone is already connected. */
    private fun afterCreate(): Screen = if (a.backup.isPaired) Screen.VaultList.also { a.queueBackup() } else Screen.BackupStep

    private fun showKit(key: RecoveryKey, vaultId: String, newVault: Boolean, doneText: String = "Vault created") {
        kitPending = true
        kit = KitState(key, vaultId, newVault, challenge(), doneText)
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
            prefs.setKitChecked(reminderNow())
            kitPending = false
            k.key.wipe()
            kit = null
            replace(if (k.newVault) afterCreate() else Screen.VaultList)
            message = k.doneText
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
        hasRecoveryKey = header?.hasRecoveryKey ?: false
        bioAutoOffered = false
        viewModelScope.launch {
            refreshBiometric()
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
                passwordTyped()
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
            kitPending = false
            hasRecoveryKey = true
            session.open(v)
            afterUnlock()
            replace(Screen.NewPassword(forced = true))
        } catch (e: WrongKeyException) {
            recoveryError = "This recovery key does not open this vault. Check that the Vault ID on your " +
                "Emergency Kit matches the one above. A kit for an older vault or an old key (replaced in Settings) won't work."
        } finally { key.wipe() }
    }

    private suspend fun openAndShowList(v: UnlockedVault) {
        hasRecoveryKey = v.header.hasRecoveryKey
        session.open(v)
        // a stale flag (e.g. from an earlier vault) never shows the card for a vault without a key
        kitPending = hasRecoveryKey && prefs.kitPending()
        replace(Screen.VaultList)
        afterUnlock()
    }

    /** Retry a pending upload, and fix a revision clash with the server while the vault is open. */
    private fun afterUnlock() {
        viewModelScope.launch { prefs.startRemindersIfNeeded(reminderNow()) }
        a.queueBackup()
        a.scope.launch { runCatching { a.resolveNewRevision() } }
        viewModelScope.launch { refreshBiometric() }
    }

    // ── password & recovery key changes ───────────────────────────────────────
    private suspend fun kdfParams() = (calibration ?: withContext(Dispatchers.Default) {
        KdfCalibration.choose(KdfCalibration::measureOnDevice)
    }.also { calibration = it }).params

    /**
     * [current] is null after a recovery-key unlock (forced flow). [rotate]: "Someone may know my old
     * password", the vault key changes too (and a vault with a recovery key gets a new one + kit).
     */
    fun setNewPassword(current: CharArray?, newPassword: CharArray, hint: String, rotate: Boolean = false) = work {
        val kdf = kdfParams()
        try {
            if (current != null) {
                val ok = withContext(Dispatchers.Default) { session.withVault(false) { it.verifyPassword(current) } }
                if (!ok) { fail("Current password is wrong"); return@work }
            }
            val (rk, id) = withContext(Dispatchers.Default) {
                session.withVault(save = true) { KeyChanges.changePassword(it, newPassword, hint.ifBlank { null }, kdf, rotate) to it.vaultId }
            }
            passwordTyped()
            if (!rotate) { replace(Screen.VaultList); message = "Master password changed"; return@work }
            val done = KeyChanges.PASSWORD_ROTATED_DONE + afterRotation()
            if (rk != null) {
                prefs.setKitPending(true)
                showKit(rk, id, newVault = false, doneText = done)
            } else { replace(Screen.VaultList); message = done }
        } finally { current?.wipe(); newPassword.wipe() }
    }

    /**
     * After a DEK rotation: the fingerprint slot wraps the old DEK, so it is turned off.
     * Returns the note for the success message (empty if fingerprint unlock wasn't on).
     */
    private fun afterRotation(): String {
        val wasOn = bio.isEnabled()
        disableBiometric(quiet = true)
        return if (wasOn) " " + KeyChanges.BIOMETRIC_OFF else ""
    }

    /**
     * New recovery key (or the first one): check the master password, rotate the DEK, save, show the kit.
     * Saved at once (like create): a kit saved as PDF must match the file even if the app locks before confirming.
     * [onDone] runs when the password was right (closes the dialog).
     */
    fun newRecoveryKey(password: CharArray, onDone: () -> Unit = {}) = work {
        val kdf = kdfParams()
        try {
            val (rk, id) = try {
                withContext(Dispatchers.Default) {
                    session.withVault(save = true) { KeyChanges.newRecoveryKey(it, password, kdf) to it.vaultId }
                }
            } catch (e: KeyChanges.WrongPassword) { fail("Master password is wrong"); return@work }
            onDone()
            passwordTyped()
            val done = KeyChanges.NEW_KEY_DONE + afterRotation()
            prefs.setKitPending(true)
            hasRecoveryKey = true
            showKit(rk, id, newVault = false, doneText = done)
        } finally { password.wipe() }
    }

    /** Step 1 of "Remove recovery key": check the master password, then [onOk] shows the confirm dialog. */
    fun checkPasswordForRemoval(password: CharArray, onOk: () -> Unit) = work {
        try {
            val ok = withContext(Dispatchers.Default) { session.withVault(false) { it.verifyPassword(password) } }
            if (ok) onOk() else fail("Master password is wrong")
        } finally { password.wipe() }
    }

    /** Step 2: drop the recovery slot and rotate the DEK (the password is checked again: it re-wraps the new DEK). */
    fun removeRecoveryKey(password: CharArray) = work {
        val kdf = kdfParams()
        try {
            try {
                withContext(Dispatchers.Default) { session.withVault(save = true) { KeyChanges.removeRecoveryKey(it, password, kdf) } }
            } catch (e: KeyChanges.WrongPassword) { fail("Master password is wrong"); return@work }
            prefs.setKitPending(false)
            kitPending = false
            hasRecoveryKey = false
            message = KeyChanges.REMOVED_DONE + afterRotation()
        } finally { password.wipe() }
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
            fail("Wrong password for this file")
        } catch (e: VaultFormatException) {
            fail("Not a desktop vault file: ${e.message}")
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

    // ── backup server ─────────────────────────────────────────────────────────
    val backupConfig get() = a.backup.config
    val backupStatus get() = a.backup.status
    val certNotAfter get() = a.backup.certNotAfter
    val vaultRev get() = session.rev

    fun backupNow() {
        a.queueBackup()
        message = "Backup queued. It runs as soon as there is a connection."
    }
    var pairingError by mutableStateOf<String?>(null)
    /** Parsed pairing text without pins, waiting for the owner's explicit confirmation. */
    var pairingWithoutPins by mutableStateOf<PairingPayload?>(null); private set
    /** Result of "Test connection" and whether it failed. */
    var connectionText by mutableStateOf<String?>(null); private set
    var connectionError by mutableStateOf(false); private set

    /** Vault ID of the vault on this phone (Settings → Backup shows it with a copy button). */
    fun currentVaultId(): String? = localVaultId()

    private fun localVaultId(): String? = if (repo.exists()) runCatching { Pvault2.peekHeader(repo.read()).vaultId }.getOrNull() else null

    /** From the QR scanner or the paste field. */
    fun submitPairing(text: String, purpose: PairPurpose) {
        pairingError = null
        val p = try { PairingPayload.parse(text) } catch (e: PairingPayload.Invalid) { pairingError = e.message; return }
        if (p.pins.isNotEmpty()) pair(p, purpose)
        else if (PairingPayload.allowWithoutPins(BuildConfig.DEBUG)) pairingWithoutPins = p      // debug: confirm dialog
        else pairingError = PairingPayload.NO_PINS_TEXT
    }

    fun confirmPairingWithoutPins(purpose: PairPurpose) { pairingWithoutPins?.let { pairingWithoutPins = null; pair(it, purpose) } }
    fun cancelPairingWithoutPins() { pairingWithoutPins = null }

    private fun pair(p: PairingPayload, purpose: PairPurpose) = work {
        try {
            val c = a.backup.pair(p)
            connectionText = null
            message = "Connected to ${c.host} as \"${c.device}\""
            a.queueBackup()                  // first upload right away (no-op without a local vault)
            afterPairing(purpose)
        } catch (e: BackupException) {
            pairingError = e.text
        }
    }

    private fun afterPairing(purpose: PairPurpose) {
        when (purpose) {
            PairPurpose.SETTINGS -> back()
            PairPurpose.RESTORE -> { back(); go(Screen.RestoreStart) }
            PairPurpose.CREATE -> replace(Screen.VaultList)
        }
    }

    fun testConnection() = work {
        connectionText = null
        try {
            connectionText = a.backup.testConnection(localVaultId()); connectionError = false
        } catch (e: BackupException) {
            connectionText = e.text; connectionError = true
        }
    }

    fun disconnectBackup() {
        a.backup.disconnect()
        connectionText = null
        message = "This phone is no longer connected to the backup server"
    }

    // ── restore, version history, test restore ────────────────────────────────
    /** Server revisions, newest first (restore list or version history). */
    var remoteRevs by mutableStateOf<List<RemoteRev>>(emptyList()); private set
    var restoreError by mutableStateOf<String?>(null)
    var restoreVaultId by mutableStateOf<String?>(null); private set
    /** Restore start: the vaults on the server; null while loading or if the server can't list them. */
    var remoteVaults by mutableStateOf<List<RemoteVault>?>(null); private set
    /** Restore start: show the "Vault ID" field (old server, list failed, or the user asked for it). */
    var manualVaultId by mutableStateOf(false)
    /** Header of the downloaded file waiting for the password (restore on a new phone). */
    var restoreHeader by mutableStateOf<VaultHeader?>(null); private set
    private var restoreRaw: ByteArray? = null
    /** Version history: the picked revision, decrypted in memory. */
    var preview by mutableStateOf<Restore.Preview?>(null); private set
    /** Version history: the picked file did not open with the current DEK, ask for its password. */
    var previewNeedsPassword by mutableStateOf(false); private set
    private var previewRaw: ByteArray? = null

    private fun target() = a.backup.requireTarget()

    /** Onboarding → "Restore from backup": pair first if needed. */
    fun startRestore() {
        restoreError = null
        if (a.backup.isPaired && !a.backup.status.value.disconnected) go(Screen.RestoreStart) else go(Screen.Pairing(PairPurpose.RESTORE))
    }

    /** Restore start: ask the server which vaults it has. A 404 (server older than Task 08) falls back to typing the ID. */
    fun loadRemoteVaults() = work {
        restoreError = null
        remoteVaults = null
        manualVaultId = false
        try {
            val list = Restore.vaultsOrNull(target())
            remoteVaults = list
            if (list == null || list.isEmpty()) manualVaultId = true
        } catch (e: BackupException) {
            restoreError = e.text
            manualVaultId = true
        }
    }

    fun loadRestoreRevisions(vaultIdText: String) = work {
        restoreError = null
        val id = Restore.normalizeVaultId(vaultIdText) ?: run { restoreError = "That is not a Vault ID (36 characters like 1a2b3c4d-…)."; return@work }
        try {
            val revs = Restore.newestFirst(target().list(id))
            if (revs.isEmpty()) { restoreError = "The server has no backups of vault $id."; return@work }
            restoreVaultId = id
            remoteRevs = revs
            go(Screen.RestoreList)
        } catch (e: BackupException) { restoreError = e.text }
    }

    fun pickRestoreRevision(rev: Long) = work {
        restoreError = null
        val id = restoreVaultId ?: return@work
        try {
            val raw = Restore.fetch(target(), id, rev)
            restoreRaw = raw
            restoreHeader = Pvault2.peekHeader(raw)
            go(Screen.RestoreUnlock)
        } catch (e: BackupException) { fail(e.text) }
    }

    /** Open the downloaded file with the master password or (if it has one) the recovery key, then save it as the local vault. */
    fun restoreUnlock(password: CharArray?, recoveryKeyText: String?) = work {
        restoreError = null
        val raw = restoreRaw ?: return@work
        if (repo.exists()) { fail("This phone already has a vault. Restore is only offered for a new vault."); return@work }
        val v = try {
            withContext(Dispatchers.Default) {
                if (password != null) Pvault2.unlockWithPassword(raw, password)
                else Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(recoveryKeyText ?: ""))
            }
        } catch (e: WrongKeyException) {
            restoreError = if (password != null) "Wrong password for this backup." else "This recovery key does not open this backup."
            return@work
        } catch (e: IllegalArgumentException) {
            restoreError = e.message ?: "That is not a valid recovery key"; return@work
        } finally { password?.wipe() }
        // The file is on the server already: record it, so the upload queued by the save has nothing to do.
        a.backup.updateStatus {
            it.copy(lastSuccessAt = System.currentTimeMillis(), lastSuccessRev = v.rev, lastSuccessSha = HttpBackupTarget.sha256Hex(raw),
                lastError = null, stopped = false, needsNewRev = false)
        }
        withContext(Dispatchers.IO) { repo.save(raw) }
        disableBiometric(quiet = true)                // restored vault: another DEK than any old blob
        prefs.clearFailures()
        prefs.setKitPending(false)
        restoreRaw = null; restoreHeader = null; remoteRevs = emptyList()
        val rev = v.rev
        if (recoveryKeyText != null) {
            hasRecoveryKey = true
            session.open(v)
            afterUnlock()
            replace(Screen.NewPassword(forced = true))
        } else openAndShowList(v)
        message = "Restored revision $rev from the server"
    }

    fun openHistory() = work {
        closePreview()
        val id = session.read { it.vaultId } ?: return@work
        try {
            remoteRevs = Restore.newestFirst(target().list(id))
            go(Screen.History)
        } catch (e: BackupException) { fail(e.text) }
    }

    fun previewRevision(rev: Long) = work {
        closePreview()
        val id = session.read { it.vaultId } ?: return@work
        try {
            val raw = Restore.fetch(target(), id, rev)
            previewRaw = raw
            val p = withContext(Dispatchers.Default) { session.read { Restore.preview(raw, it.exportDekForBiometric()) } }
            if (p != null) preview = p else previewNeedsPassword = true
        } catch (e: BackupException) { fail(e.text) }
    }

    fun previewWithPassword(password: CharArray) = work {
        val raw = previewRaw ?: return@work
        try {
            val v = withContext(Dispatchers.Default) { Pvault2.unlockWithPassword(raw, password) }
            preview = Restore.Preview(v.rev, v.header.updated, v.payload)
            previewNeedsPassword = false
            v.wipe()
        } catch (e: WrongKeyException) { fail("Wrong password for this version") } finally { password.wipe() }
    }

    fun closePreview() { preview = null; previewNeedsPassword = false; previewRaw = null }

    /** Snapshot the current vault (done by every save), then save the old content as a new revision above everything known. */
    fun restoreVersion() = work {
        val p = preview ?: return@work
        val id = session.read { it.vaultId } ?: return@work
        val remote = try { target().latest(id)?.rev ?: 0L } catch (e: BackupException) { fail(e.text); return@work }
        val newRev = Restore.rollbackRev(session.rev.value, remote)
        session.reseal(newRev, p.payload)
        closePreview()
        replace(Screen.VaultList)
        message = "Restored the version of revision ${p.rev} as revision $newRev. The state before is kept in local snapshots and on the server."
    }

    fun testRestore() = work {
        if (!session.isUnlocked) return@work           // code review F5: never test with a made-up key
        val id = session.read { it.vaultId } ?: return@work
        val local = session.payload.value?.activeEntries?.size ?: 0
        try {
            val r = Restore.testRestore(target(), id, { session.read { it.exportDekForBiometric() } ?: throw IllegalStateException("vault is locked") }, local, session.rev.value)
            if (r.ok) {
                a.backup.updateStatus { it.copy(testRestoreAt = System.currentTimeMillis(), testRestoreText = r.text) }
                message = "Test restore OK"
            } else fail(r.text)
        } catch (e: BackupException) { fail(e.text) }
    }

    val testRestoreDismissedAt get() = prefs.testRestoreDismissedAt

    /** Create flow, step 3 → "Skip": the red "No backup set up" chip stays on the list. */
    fun skipBackupStep() = replace(Screen.VaultList)

    /** Wall clock for reminders and the fingerprint N-day rule; debug builds can fast-forward it. */
    fun reminderNow(): Long = System.currentTimeMillis() + if (BuildConfig.DEBUG) reminderTimes.debugOffsetMs else 0L

    fun dismissTestRestoreReminder() = viewModelScope.launch { prefs.setTestRestoreDismissed(System.currentTimeMillis()) }

    // ── fingerprint unlock ────────────────────────────────────────────────────
    private val bio = a.biometric
    /** Fingerprint unlock is set up for this vault (Settings switch). */
    var biometricEnabled by mutableStateOf(false); private set
    /** The unlock screen may offer the fingerprint now (BiometricPolicy says NONE). */
    var biometricAllowed by mutableStateOf(false); private set
    /** Why the master password is required instead (reboot, N days, failures, new fingerprint). */
    var biometricReason by mutableStateOf<String?>(null); private set
    /** The prompt opens by itself once per visit of the unlock screen. */
    var bioAutoOffered = false

    private fun bootCount() = Global.getInt(getApplication<Application>().contentResolver, Global.BOOT_COUNT, 0)

    private fun bioMatchesVault() = bio.isEnabled() && bio.vaultId() != null && bio.vaultId() == localVaultId()

    suspend fun refreshBiometric() {
        biometricEnabled = bioMatchesVault()
        val st = prefs.biometricState(biometricEnabled)
        val need = BiometricPolicy.check(st, bootCount(), reminderNow())
        biometricAllowed = need == BiometricPolicy.Need.NONE
        biometricReason = BiometricPolicy.reason(need, st.maxDays)
    }

    /** The master password was typed correctly: restart the N-day clock, note the boot, reset fingerprint failures. */
    private suspend fun passwordTyped() {
        prefs.recordPasswordTyped(reminderNow(), bootCount())
        if (prefs.biometricState(false).invalidated) {
            prefs.setBiometricInvalidated(false)
            message = "Fingerprints changed on this phone. Turn on fingerprint unlock again in Settings → Security."
        }
        refreshBiometric()
    }

    /** Cipher for the unlock prompt, or null (then the password is needed) if the key was invalidated. */
    fun biometricUnlockCipher(): Cipher? = try { bio.decryptCipher() } catch (e: BiometricVault.Invalidated) {
        bio.disable()
        viewModelScope.launch { prefs.setBiometricInvalidated(true); refreshBiometric() }
        null
    }

    fun onBiometricFailed() = viewModelScope.launch { prefs.addBiometricFailure(); refreshBiometric() }

    /** The system locked the sensor (too many attempts): the master password is required. */
    fun onBiometricLockout() = viewModelScope.launch { prefs.setBiometricFailures(BiometricPolicy.MAX_FAILURES); refreshBiometric() }

    fun unlockWithBiometric(cipher: Cipher) = work {
        val raw = withContext(Dispatchers.IO) { repo.read() }
        val dek = try { bio.unwrap(cipher) } catch (e: Exception) { fail("Fingerprint unlock failed. Enter your master password."); return@work }
        try {
            val v = withContext(Dispatchers.Default) { Pvault2.unlockWithDek(raw, dek) }
            prefs.setBiometricFailures(0)
            prefs.clearFailures()
            openAndShowList(v)
        } catch (e: WrongKeyException) {
            bio.disable()
            refreshBiometric()
            unlockError = "Fingerprint unlock doesn't match this vault any more. Enter your master password."
        }
    }

    /** Settings: step 1 of turning on, check the master password; [onOk] then shows the fingerprint prompt. */
    fun checkPasswordForBiometric(password: CharArray, onOk: (Cipher) -> Unit) = work {
        try {
            val ok = withContext(Dispatchers.Default) { session.withVault(false) { it.verifyPassword(password) } }
            if (!ok) { fail("Master password is wrong"); return@work }
            passwordTyped()
            onOk(bio.encryptCipher())
        } finally { password.wipe() }
    }

    /** Step 2: the fingerprint was accepted, wrap the DEK with the authenticated cipher. */
    fun finishEnableBiometric(cipher: Cipher) = work {
        val (dek, id) = session.read { it.exportDekForBiometric() to it.vaultId } ?: return@work
        bio.store(cipher, dek, id)
        prefs.setBiometricInvalidated(false)
        prefs.setBiometricFailures(0)
        refreshBiometric()
        message = "Fingerprint unlock is on"
    }

    fun disableBiometric(quiet: Boolean = false) {
        bio.disable()
        biometricEnabled = false
        biometricAllowed = false
        if (!quiet) message = "Fingerprint unlock is off"
    }

    val biometricDays get() = prefs.biometricDays
    fun setBiometricDays(days: Int) = viewModelScope.launch { prefs.setBiometricDays(days) }

    // ── password reminders ────────────────────────────────────────────────────
    /** Reminder clocks; kept current from DataStore (collected in init). */
    var reminderTimes by mutableStateOf(Reminders.Times()); private set
    /** Practice card: the last answer was wrong (offer the hint and a new password). */
    var practiceWrong by mutableStateOf(false); private set
    /** Hint of the open vault, for the practice card. */
    var practiceHint by mutableStateOf<String?>(null); private set

    fun practicePassword(password: CharArray) = work {
        try {
            val ok = withContext(Dispatchers.Default) { session.withVault(false) { it.verifyPassword(password) } }
            if (ok) {
                practiceWrong = false
                passwordTyped()
                message = "Correct. Your master password is still in your head."
            } else {
                practiceWrong = true
                practiceHint = session.read { it.header.hint }
            }
        } finally { password.wipe() }
    }

    fun dismissPractice() = viewModelScope.launch { practiceWrong = false; prefs.setPracticeDismissed(reminderNow()) }

    /** Practice failed: set a new master password while the vault is open (no current password needed). */
    fun newPasswordFromPractice() { practiceWrong = false; go(Screen.NewPassword(forced = false, withoutCurrent = true)) }

    fun kitStillSafe() = viewModelScope.launch { prefs.setKitChecked(reminderNow()); message = "Thanks. Next reminder in a year." }


    /** Debug builds: move the reminder clock forward by [days] (0 = reset). */
    fun debugClockForward(days: Int) = viewModelScope.launch {
        prefs.setDebugClockOffset(if (days == 0) 0L else reminderTimes.debugOffsetMs + days * 24 * 3_600_000L)
        refreshBiometric()
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { block() } catch (e: Exception) {
                fail("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = false }
        }
    }

    // Last in the class: init runs in declaration order and the coroutines below start immediately
    // (Main.immediate), so every property above must already be initialised.
    init {
        screen = if (session.isUnlocked) Screen.VaultList else if (repo.exists()) Screen.Unlock else Screen.Onboarding
        viewModelScope.launch { session.lockEvents.collect { onLocked(it) } }
        viewModelScope.launch {
            prefs.reminderTimes.collect {
                val clockMoved = it.debugOffsetMs != reminderTimes.debugOffsetMs
                reminderTimes = it
                if (clockMoved) refreshBiometric()
            }
        }
        if (screen == Screen.Unlock) prepareUnlock()
    }
}
