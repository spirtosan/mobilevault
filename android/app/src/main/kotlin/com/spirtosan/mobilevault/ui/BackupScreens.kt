package com.spirtosan.mobilevault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.spirtosan.mobilevault.backup.BackupErrors
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
