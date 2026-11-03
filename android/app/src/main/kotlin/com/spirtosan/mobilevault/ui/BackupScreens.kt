package com.spirtosan.mobilevault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.spirtosan.mobilevault.backup.BackupErrors
import com.spirtosan.mobilevault.backup.BackupHealth
import com.spirtosan.mobilevault.backup.CertExpiry
import com.spirtosan.mobilevault.backup.RemoteRev
import com.spirtosan.mobilevault.backup.RemoteVault
import com.spirtosan.mobilevault.backup.Restore
import com.spirtosan.mobilevault.backup.TestRestoreReminder
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
internal fun localTimeMs(ms: Long): String = DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** One line in Settings → Backup card. */
@Composable
internal fun BackupSummary(vm: AppViewModel) {
    val c by vm.backupConfig.collectAsState()
    val st by vm.backupStatus.collectAsState()
    val cfg = c
    when {
        cfg == null -> WarnText("No backup set up. Connect this phone to your own server.")
        st.disconnected -> ErrorText(BackupErrors.DISCONNECTED_TEXT)
        else -> Muted("Server ${cfg.host}, this phone: ${cfg.device}")
    }
}

@Composable
internal fun BackupScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val c by vm.backupConfig.collectAsState()
    val st by vm.backupStatus.collectAsState()
    var confirmDisconnect by remember { mutableStateOf(false) }
    Page("Backup", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        val cfg = c
        if (cfg == null) {
            PanelCard(title = "Backup server") {
                Text("Encrypted backups to your own server. Only the already-encrypted vault file is sent; " +
                    "the server never sees your password or your entries.", color = Palette.fgPrimary)
                WarnText("No backup set up.", "noBackup")
                MainButton("Connect to backup server", { vm.go(Screen.Pairing(PairPurpose.SETTINGS)) },
                    Modifier.fillMaxWidth().testTag("connectBackup"), Icons.Filled.CloudUpload)
            }
            return@Page
        }
        PanelCard(title = "Backup server") {
            if (st.disconnected) {
                ErrorText(BackupErrors.DISCONNECTED_TEXT, "disconnected")
                MainButton("Connect again", { vm.go(Screen.Pairing(PairPurpose.SETTINGS)) }, Modifier.fillMaxWidth(), Icons.Filled.QrCodeScanner)
            }
            Text("Server: ${cfg.host}", color = Palette.fgPrimary, modifier = Modifier.testTag("backupHost"))
            Text("This phone: ${cfg.device}", color = Palette.fgPrimary, modifier = Modifier.testTag("backupDevice"))
            Muted("Connected ${localTimeMs(cfg.pairedAt)}. Key in Android Keystore" + if (cfg.strongBox) " (StrongBox)." else ".")
            val certNotAfter by vm.certNotAfter.collectAsState()
            if (certNotAfter > 0) {
                val line = "Certificate valid until ${CertExpiry.date(certNotAfter)}"
                if (CertExpiry.soon(certNotAfter, System.currentTimeMillis()))
                    WarnText("$line. Connect this phone again (mvault-admin pair) before then, or backups stop.", "certExpiry")
                else Muted(line)
            }
            remember { vm.currentVaultId() }?.let { id ->
                Text("Vault ID", color = Palette.fgSecondary, style = MaterialTheme.typography.labelMedium)
                Text(id, color = Palette.fgPrimary, modifier = Modifier.testTag("vaultId"))
                ColorButton("Copy Vault ID", { vm.copy("Vault ID", id) }, Palette.teal, Modifier.testTag("copyVaultId"), Icons.Filled.ContentCopy)
                Muted("Needed only if a new phone can't list the backups on the server. Keep it with your Emergency Kit.")
            }
            if (cfg.pins.isEmpty()) WarnText("No server pins: the connection relies on public certificate authorities only.")
            ColorButton("Test connection", { vm.testConnection() }, Palette.blue, Modifier.fillMaxWidth().testTag("testConnection"),
                Icons.Filled.NetworkCheck, enabled = !vm.busy)
            vm.connectionText?.let {
                if (vm.connectionError) ErrorText(it, "connectionResult")
                else Text(it, color = Palette.green, modifier = Modifier.testTag("connectionResult"))
            }
        }
        PanelCard(title = "Status") {
            if (st.lastSuccessAt > 0) Text("Last backup: ${localTimeMs(st.lastSuccessAt)} (revision ${st.lastSuccessRev})",
                color = Palette.fgPrimary, modifier = Modifier.testTag("lastBackup"))
            else WarnText("No successful backup yet.", "lastBackup")
            val rev by vm.vaultRev.collectAsState()
            if (rev > st.lastSuccessRev) WarnText("Changes not backed up yet (revision $rev on this phone).", "unsynced")
            st.lastError?.let { if (st.stopped || st.disconnected) ErrorText(it, "lastError") else WarnText(it, "lastError") }
            ColorButton("Back up now", { vm.backupNow() }, Palette.green, Modifier.fillMaxWidth().testTag("backupNow"),
                Icons.Filled.CloudUpload, enabled = !st.disconnected)
            ColorButton("Version history", { vm.openHistory() }, Palette.violet, Modifier.fillMaxWidth().testTag("history"),
                Icons.Filled.History, enabled = !st.disconnected && !vm.busy)
            ColorButton("Test restore", { vm.testRestore() }, Palette.blue, Modifier.fillMaxWidth().testTag("testRestore"),
                Icons.Filled.Verified, enabled = !st.disconnected && !vm.busy)
            if (st.testRestoreAt > 0) Text("Last test restore ${localTimeMs(st.testRestoreAt)}: ${st.testRestoreText ?: ""}",
                color = Palette.green, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("testRestoreResult"))
            val s by vm.settings.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.backupWifiOnly, { vm.updateSettings(s.copy(backupWifiOnly = it)) }, Modifier.testTag("wifiOnly"),
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.orange, checkedThumbColor = Palette.onOrange))
                Spacer(Modifier.width(10.dp)); Text("Only on Wi-Fi", color = Palette.fgPrimary)
            }
            Muted("Uploads run in the background after every change and are retried each time you open the app. " +
                "On Xiaomi (HyperOS) allow Autostart and set Battery saver to \"No restrictions\" for Mobile Vault, " +
                "otherwise background uploads may be delayed.")
        }
        PanelCard(title = "This phone") {
            OutlineButton("Disconnect this phone", { confirmDisconnect = true }, Palette.red,
                Modifier.fillMaxWidth().testTag("disconnectBackup"), Icons.Filled.LinkOff)
        }
    }
    val cfg = c
    if (confirmDisconnect && cfg != null) ConfirmDialog(
        "Disconnect this phone?",
        "This deletes the backup key, certificate and token on this phone. Backups already on the server stay there.\n\n" +
            "Also run  mvault-admin revoke ${cfg.device}  on the server, so the old certificate can't be used any more.",
        confirm = "Disconnect", confirmColor = Palette.red, confirmTag = "disconnectConfirm",
        onConfirm = { confirmDisconnect = false; vm.disconnectBackup() }, onDismiss = { confirmDisconnect = false },
    )
}

@Composable
internal fun PairingScreen(vm: AppViewModel, snackbar: SnackbarHostState, purpose: PairPurpose) {
    var text by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        vm.externalDone()
        r.contents?.let { vm.submitPairing(it, purpose) }
    }
    Page("Connect to backup server", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("On the server, run:", color = Palette.fgPrimary)
            Text("mvault-admin pair <name for this phone>", color = Palette.orange, modifier = Modifier.testTag("pairCommand"))
            Muted("Then scan the QR code it shows, or paste the \"Payload\" line. The code works once, for 10 minutes.")
            ColorButton("Scan QR code", {
                vm.externalStarted()
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR code from mvault-admin pair")
                    .setBeepEnabled(false).setOrientationLocked(false))
            }, Palette.steel, Modifier.fillMaxWidth().testTag("scanQr"), Icons.Filled.QrCodeScanner, enabled = !vm.busy)
        }
        PanelCard(title = "Or paste the pairing text") {
            PlainField(text, { text = it; vm.pairingError = null }, "Pairing text", singleLine = false, tag = "pairText",
                keyboardType = KeyboardType.Uri)   // no auto-capitals or auto-spaces in the JSON
            MainButton("Connect", { vm.submitPairing(text, purpose) }, Modifier.fillMaxWidth().testTag("pairConnect"),
                Icons.Filled.CloudUpload, enabled = text.isNotBlank() && !vm.busy)
        }
        vm.pairingError?.let { ErrorText(it, "pairError") }
    }
    if (vm.pairingWithoutPins != null) ConfirmDialog(
        "No server pins (debug build)",
        "This pairing code has no server key pins. The app could then be fooled by anyone with a certificate from a " +
            "public authority for this name. Release builds refuse such a code. Only continue if you know why the server has no pins configured.",
        confirm = "Connect anyway", confirmColor = Palette.red, confirmTag = "noPinsConfirm",
        onConfirm = { vm.confirmPairingWithoutPins(purpose) }, onDismiss = { vm.cancelPairingWithoutPins() },
    )
}

/** Backup health on the vault list: green / amber / red, plus "Changes not backed up yet". Tap → Backup. */
@Composable
internal fun BackupChip(vm: AppViewModel) {
    val c by vm.backupConfig.collectAsState()
    val st by vm.backupStatus.collectAsState()
    val rev by vm.vaultRev.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    val certNotAfter by vm.certNotAfter.collectAsState()
    val chip = BackupHealth.of(c != null, st, rev, now, certNotAfter)
    val color = when (chip.level) {
        BackupHealth.Level.GREEN -> Palette.green
        BackupHealth.Level.AMBER -> Palette.orange
        BackupHealth.Level.RED -> Palette.red
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.bgPanel, RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Backup) }
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("backupChip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(chip.text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("backupChipText"))
            if (chip.unsynced) Text("Changes not backed up yet", color = Palette.fgSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Filled.CloudUpload, null, tint = Palette.fgSecondary, modifier = Modifier.size(18.dp))
    }
}

// ── restore, version history, test restore ────────────────────────────────────

/** Create flow, step 3 of 3: connect the new vault to the backup server, or skip. */
@Composable
internal fun BackupStepScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var confirmSkip by remember { mutableStateOf(false) }
    Page("Create vault", snackbar, busy = vm.busy) {
        BrandHeader("Step 3 of 3: backup")
        PanelCard(title = "Back up to your server") {
            Text("Your vault is saved on this phone. If the phone is lost or broken, only a backup brings it back. " +
                "Backups go to your own server, encrypted; the server never sees your password or entries.", color = Palette.fgPrimary)
            MainButton("Connect now (recommended)", { vm.go(Screen.Pairing(PairPurpose.CREATE)) },
                Modifier.fillMaxWidth().testTag("backupStepConnect"), Icons.Filled.CloudUpload)
            OutlineButton("Skip", { confirmSkip = true }, Palette.fgPrimary, Modifier.fillMaxWidth().testTag("backupStepSkip"),
                borderColor = Palette.fgSecondary)
        }
    }
    if (confirmSkip) ConfirmDialog(
        "Skip the backup?", "You can connect later in Settings → Backup. Until then the vault exists only on this phone.",
        confirm = "Skip", confirmColor = Palette.red, dismiss = "Back", confirmTag = "backupSkipConfirm",
        onConfirm = { confirmSkip = false; vm.skipBackupStep() }, onDismiss = { confirmSkip = false },
    )
}

/** Restore on a new phone, step 1: which vault. The server lists them (`GET /vaults`); typing the Vault ID is the fallback. */
@Composable
internal fun RestoreStartScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var id by remember { mutableStateOf("") }
    Page("Restore from backup", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        val list = vm.remoteVaults
        if (list != null && list.isNotEmpty()) {
            Muted("Vaults backed up on your server, newest first. Tap the one to restore.")
            list.forEach { v -> VaultRow(v) { vm.loadRestoreRevisions(v.vaultId) } }
        } else if (list != null) {
            WarnText("The server has no backups yet.", "noRemoteVaults")
        }
        if (!vm.manualVaultId) {
            vm.restoreError?.let { ErrorText(it, "restoreError") }
            if (list != null) OutlineButton("Enter Vault ID manually", { vm.manualVaultId = true; vm.restoreError = null }, Palette.fgSecondary,
                Modifier.fillMaxWidth().testTag("restoreManual"), Icons.Filled.Keyboard)
        } else PanelCard(title = "Vault ID") {
            if (list == null) Muted("This server can't list its vaults. Enter the Vault ID instead.")
            Muted("It is printed on your Emergency Kit and shown in Settings → Backup. On the server,  mvault-admin stats  lists the IDs of all backed-up vaults.")
            PlainField(id, { id = it; vm.restoreError = null }, "Vault ID", tag = "restoreVaultId", keyboardType = KeyboardType.Uri)
            vm.restoreError?.let { ErrorText(it, "restoreError") }
            MainButton("Show backups", { vm.loadRestoreRevisions(id) }, Modifier.fillMaxWidth().testTag("restoreLoad"),
                Icons.Filled.CloudDownload, enabled = id.isNotBlank() && !vm.busy)
        }
    }
}

/** One vault on the server: "Vault 1a2b…, revision 42" / "3 Nov 2026, 10:00 · from xiaomi". */
@Composable
internal fun VaultRow(v: RemoteVault, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.bgPanel, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("vault:${v.vaultId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${Restore.shortId(v.vaultId)}, revision ${v.latestRev}", color = Palette.fgPrimary, fontWeight = FontWeight.Bold)
            Muted(listOfNotNull(
                v.latestTime.takeIf { it.isNotEmpty() }?.let { localTime(it) },
                v.device.takeIf { it.isNotEmpty() }?.let { "from $it" },
                v.revisions.takeIf { it > 0 }?.let { if (it == 1) "1 version" else "$it versions" },
            ).joinToString(" · "))
        }
        Icon(Icons.Filled.CloudDownload, null, tint = Palette.fgSecondary)
    }
}

/** One server revision as a tappable row. */
@Composable
internal fun RevisionRow(r: RemoteRev, newest: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.bgPanel, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("rev:${r.rev}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Revision ${r.rev}" + if (newest) "  (newest)" else "", color = Palette.fgPrimary, fontWeight = FontWeight.Bold)
            Muted("${localTime(r.received)} · ${"%.1f".format(r.size / 1024.0)} KB" + if (r.device.isNotEmpty()) " · ${r.device}" else "")
        }
        Icon(Icons.Filled.History, null, tint = Palette.fgSecondary)
    }
}

/** Restore step 2: pick a revision (newest first). */
@Composable
internal fun RestoreListScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    Page("Choose a backup", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        Muted("Vault ${vm.restoreVaultId}. The newest backup is usually the right one.")
        vm.remoteRevs.forEachIndexed { i, r -> RevisionRow(r, i == 0) { vm.pickRestoreRevision(r.rev) } }
    }
}

/** Restore step 3: unlock the downloaded file with the master password or the recovery key. */
@Composable
internal fun RestoreUnlockScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val h = vm.restoreHeader ?: return
    var useKey by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Page("Unlock the backup", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("Revision ${h.rev}, saved ${localTime(h.updated)}", color = Palette.fgPrimary)
            if (!useKey) {
                PasswordField(pw, { pw = it; vm.restoreError = null }, "Master password", tag = "restorePw", imeAction = ImeAction.Go,
                    onDone = { if (pw.isNotEmpty()) { vm.restoreUnlock(pw.toCharArray(), null); pw = "" } })
                h.hint?.let { Muted("Hint: $it") }
            } else {
                KeyField(key, { key = it; vm.restoreError = null }, "Recovery key", tag = "restoreKey")
            }
            vm.restoreError?.let { ErrorText(it, "restoreError") }
            MainButton("Restore", {
                if (useKey) vm.restoreUnlock(null, key) else { vm.restoreUnlock(pw.toCharArray(), null); pw = "" }
            }, Modifier.fillMaxWidth().testTag("restoreUnlock"), Icons.Filled.LockOpen,
                enabled = !vm.busy && if (useKey) key.isNotBlank() else pw.isNotEmpty())
            if (h.hasRecoveryKey) TextButton(onClick = { useKey = !useKey; vm.restoreError = null }) {
                Text(if (useKey) "Use the master password instead" else "Forgot password? Use recovery key", color = Palette.navFg)
            }
            Muted("The backup becomes the vault on this phone, and backups continue from this revision.")
        }
    }
}

/** Settings → Backup → Version history: preview an old revision and restore it as a new one. */
@Composable
internal fun HistoryScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var confirm by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    Page("Version history", snackbar, onBack = { vm.closePreview(); vm.back() }, busy = vm.busy) {
        val p = vm.preview
        if (p != null) PanelCard(Modifier.testTag("preview"), title = "Revision ${p.rev}") {
            Text("${p.entries} entries, saved ${localTime(p.updated)}", color = Palette.fgPrimary, modifier = Modifier.testTag("previewText"))
            Muted(p.payload.activeEntries.take(8).joinToString(", ") { it.service } + if (p.entries > 8) ", …" else "")
            ColorButton("Restore this version", { confirm = true }, Palette.violet, Modifier.fillMaxWidth().testTag("restoreVersion"),
                Icons.Filled.Restore, enabled = !vm.busy)
            SecondaryButton("Close", { vm.closePreview() }, Modifier.fillMaxWidth())
        }
        if (vm.previewNeedsPassword) PanelCard(title = "Password of that version") {
            Muted("This version doesn't open with the current vault key (it was saved before a key change). " +
                "Enter the master password this version was saved with.")
            PasswordField(pw, { pw = it }, "Master password", tag = "previewPw")
            MainButton("Open", { vm.previewWithPassword(pw.toCharArray()); pw = "" }, Modifier.fillMaxWidth(), enabled = pw.isNotEmpty())
        }
        Muted("Backups on the server, newest first. Tap one to see what it contains.")
        vm.remoteRevs.forEachIndexed { i, r -> RevisionRow(r, i == 0) { vm.previewRevision(r.rev) } }
    }
    val p = vm.preview
    if (confirm && p != null) ConfirmDialog(
        "Restore revision ${p.rev}?",
        "The vault will contain the ${p.entries} entries of that version. Your current state is kept as a local snapshot and on the server; " +
            "the restored version is saved as a new revision. Your master password and recovery key stay as they are now.",
        confirm = "Restore", confirmColor = Palette.violet, confirmTag = "restoreVersionConfirm",
        onConfirm = { confirm = false; vm.restoreVersion() }, onDismiss = { confirm = false },
    )
}

/** On the vault list every 30 days: "Test your backup". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TestRestoreCard(vm: AppViewModel) {
    val c by vm.backupConfig.collectAsState()
    val st by vm.backupStatus.collectAsState()
    val dismissed by vm.testRestoreDismissedAt.collectAsState(0L)
    val now = vm.reminderNow()
    if (!TestRestoreReminder.due(c != null, st, c?.pairedAt ?: 0, dismissed, now)) return
    PanelCard(Modifier.testTag("testRestoreCard"), title = "Test your backup") {
        Muted("Check that the latest backup on your server can really be decrypted. Nothing is changed.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorButton("Test restore now", { vm.testRestore() }, Palette.blue, Modifier.testTag("testRestoreNow"), Icons.Filled.Verified)
            OutlineButton("Later", { vm.dismissTestRestoreReminder() }, Palette.fgSecondary)
        }
    }
}
