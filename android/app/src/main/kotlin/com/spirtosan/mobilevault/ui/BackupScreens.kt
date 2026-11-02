package com.spirtosan.mobilevault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.spirtosan.mobilevault.backup.BackupErrors
import com.spirtosan.mobilevault.backup.BackupHealth
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
        "No server pins",
        "This pairing code has no server key pins. The app could then be fooled by anyone with a certificate from a " +
            "public authority for this name. Only continue if you know why the server has no pins configured.",
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
    val chip = BackupHealth.of(c != null, st, rev, now)
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
