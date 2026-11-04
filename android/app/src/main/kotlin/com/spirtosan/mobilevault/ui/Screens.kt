package com.spirtosan.mobilevault.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.spirtosan.mobilevault.BuildConfig
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.data.ImportInto
import com.spirtosan.mobilevault.data.SectionFilter
import com.spirtosan.mobilevault.data.Settings
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.security.KeyChanges
import com.spirtosan.mobilevault.security.MasterPasswordPolicy
import com.spirtosan.mobilevault.security.PasswordGenerator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Import preview: "New section…" (not a section id). */
private const val NEW_SECTION = "*new*"

/** Export screen: "All entries" (not a section id). */
private const val ALL_ENTRIES = "*all*"

/** Export files for the desktop app keep the old minimum; the master password rule is [MasterPasswordPolicy]. */
private const val MIN_EXPORT_LENGTH = 8

@Composable
fun AppRoot(vm: AppViewModel, finish: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { text ->
            val error = vm.messageError
            vm.messageShown()
            snackbar.showSnackbar(Message(text, error))
        }
    }
    BackHandler { if (!vm.back()) finish() }
    when (val s = vm.screen) {
        Screen.Loading -> Unit
        Screen.Onboarding -> OnboardingScreen(vm, snackbar)
        is Screen.Create -> CreateScreen(vm, snackbar, s)
        Screen.EmergencyKit -> KitScreen(vm, snackbar)
        Screen.CheckRecoveryKey -> CheckRecoveryKeyScreen(vm, snackbar)
        Screen.Unlock -> UnlockScreen(vm, snackbar)
        Screen.Recovery -> RecoveryScreen(vm, snackbar)
        is Screen.NewPassword -> NewPasswordScreen(vm, snackbar, s.forced, s.withoutCurrent)
        Screen.VaultList -> ListScreen(vm, snackbar)
        is Screen.Detail -> DetailScreen(vm, snackbar, s.id)
        is Screen.Edit -> EditScreen(vm, snackbar, s.id)
        Screen.Settings -> SettingsScreen(vm, snackbar)
        Screen.Sections -> SectionsScreen(vm, snackbar)
        // keyed per import: a new file always gets a fresh, empty password field (Task 11)
        Screen.ImportPassword -> key(vm.importSeq) { ImportPasswordScreen(vm, snackbar) }
        Screen.ImportServer -> ImportServerScreen(vm, snackbar)
        Screen.ImportServerRevs -> ImportServerRevsScreen(vm, snackbar)
        is Screen.ImportPreview -> ImportPreviewScreen(vm, snackbar, s.count)
        Screen.Export -> ExportScreen(vm, snackbar)
        Screen.About -> AboutScreen(vm, snackbar)
        Screen.Backup -> BackupScreen(vm, snackbar)
        is Screen.Pairing -> PairingScreen(vm, snackbar, s.purpose)
        Screen.BackupStep -> BackupStepScreen(vm, snackbar)
        Screen.RestoreStart -> RestoreStartScreen(vm, snackbar)
        Screen.RestoreList -> RestoreListScreen(vm, snackbar)
        Screen.RestoreUnlock -> RestoreUnlockScreen(vm, snackbar)
        Screen.History -> HistoryScreen(vm, snackbar)
    }
}

/** System "open file" picker for a desktop .vault file. */
@Composable
private fun rememberImportPicker(vm: AppViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        vm.externalDone()
        if (uri != null) vm.importPicked(uri)
    }
    return { vm.externalStarted(); launcher.launch(arrayOf("*/*")) }
}

private val MODIFIED_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

/** "2026-11-02T22:43:42Z" → "3 Nov 2026, 00:43" in the phone's time zone; unparsable values are shown as stored. */
internal fun localTime(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { MODIFIED_FORMAT.format(Instant.parse(iso).atZone(zone)) }.getOrDefault(iso)

@Composable
internal fun ErrorText(text: String, tag: String? = null) =
    Text(text, color = Palette.red, style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)

@Composable
internal fun WarnText(text: String, tag: String? = null) =
    Text(text, color = Palette.orange, style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)

// ── onboarding & create ──────────────────────────────────────────────────────
@Composable
private fun OnboardingScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val pickImport = rememberImportPicker(vm)
    val pickRestoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        vm.externalDone()
        if (uri != null) vm.restoreFilePicked(uri)
    }
    Page("", snackbar, busy = vm.busy) {
        BrandHeader("Your passwords, encrypted on this phone.")
        PanelCard {
            Muted("Only the encrypted vault file ever leaves the phone: to your own backup server, if you connect one.")
            MainButton("Create new vault", { vm.go(Screen.Create(null)) }, Modifier.fillMaxWidth().testTag("create"), Icons.Filled.Add)
            ColorButton("Import from desktop (.vault)", pickImport, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.FolderOpen)
            ColorButton("Restore from backup", { vm.startRestore() }, Palette.steel, Modifier.fillMaxWidth().testTag("restore"), Icons.Filled.CloudDownload)
            ColorButton("Restore from a file", { vm.externalStarted(); pickRestoreFile.launch(arrayOf("*/*")) }, Palette.steel,
                Modifier.fillMaxWidth().testTag("restoreFile"), Icons.Filled.FolderOpen, enabled = !vm.busy)
            Muted("\"Restore from a file\" opens a full copy (.pvault) saved with Settings → Save a full copy.")
        }
    }
    if (vm.restoreFileIsDesktop) ConfirmDialog(
        "This is a desktop file",
        "This file is from the desktop app (.vault), not a full copy of a Mobile Vault. Create a new vault with its entries instead? " +
            "You'll enter the desktop file's password, then choose a master password for the new vault.",
        confirm = "Create new vault", confirmColor = Palette.violet, confirmTag = "restoreDesktopCreate",
        onConfirm = { vm.restoreFileAsDesktopImport() }, onDismiss = { vm.cancelRestoreFileDesktop() },
    )
}

@Composable
private fun CalibrationInfo(vm: AppViewModel) {
    val c = vm.calibration
    if (c == null) { Muted("Measuring how fast this phone unlocks…"); return }
    // Task 13: plain words first; the Argon2 parameters only behind a collapsed "Details" row.
    var details by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Muted(unlockTimeText(c.unlockMs), "unlockTime")
        TextButton(onClick = { details = !details }, modifier = Modifier.testTag("calibDetails")) {
            Text(if (details) "Hide details" else "Details", color = Palette.navFg, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (details) Muted("Argon2id, %d MiB memory, t=%d, p=%d. Measured on this phone when the vault is created."
        .format(c.params.mKiB / 1024, c.params.t, c.params.p), "calibParams")
}

/** "Unlock takes about 2 seconds on this phone." (whole seconds); under ~1 s: "less than a second". */
internal fun unlockTimeText(ms: Long): String {
    if (ms < 950) return "Unlock takes less than a second on this phone."
    val s = ((ms + 500) / 1000).coerceAtLeast(1)
    return "Unlock takes about $s second${if (s == 1L) "" else "s"} on this phone."
}

@Composable
private fun newPasswordFields(
    vm: AppViewModel,
    pw: String, onPw: (String) -> Unit,
    confirm: String, onConfirm: (String) -> Unit,
    hint: String, onHint: (String) -> Unit,
): Boolean {
    PasswordField(pw, onPw, "Master password", tag = "pw")
    StrengthMeter(pw)
    val chars = pw.toCharArray()
    val verdict = MasterPasswordPolicy.check(chars)
    if (pw.isNotEmpty() && verdict == MasterPasswordPolicy.Verdict.TOO_SHORT)
        Muted("Use at least ${MasterPasswordPolicy.MIN_LENGTH} characters.")
    // A warning, not a block (owner's choice): the vault file or a backup can be attacked offline.
    if (verdict == MasterPasswordPolicy.Verdict.WEAK) WarnText(
        "Short passwords are easier to guess if someone copies the vault file or a backup. " +
            "Fine for daily use if your phone has a screen lock.", "weakWarning",
    )
    PasswordField(confirm, onConfirm, "Repeat master password", tag = "pw2", isError = confirm.isNotEmpty() && confirm != pw)
    PlainField(hint, onHint, "Hint (optional)", tag = "hint")
    val hintProblem = if (hint.isBlank()) null else vm.hintProblem(hint, chars)
    chars.fill('\u0000')
    if (hintProblem != null) ErrorText(hintProblem)
    Muted("The hint is stored unencrypted and shown on the unlock screen to anyone holding the phone. Never put the password itself in it.")
    CalibrationInfo(vm)
    return verdict != MasterPasswordPolicy.Verdict.TOO_SHORT && pw == confirm && hintProblem == null && vm.calibration != null && !vm.busy
}

@Composable
private fun CreateScreen(vm: AppViewModel, snackbar: SnackbarHostState, s: Screen.Create) {
    var pw by rememberSecret()
    var confirm by rememberSecret()
    var hint by rememberSaveable { mutableStateOf("") }
    var keyStep by remember { mutableStateOf(false) }      // step 2: recovery key or skip (not saveable: the password isn't)
    if (keyStep) {
        BackHandler { keyStep = false }
        RecoveryKeyStep(vm, snackbar, onBack = { keyStep = false }) { withKey ->
            vm.createVault(pw.toCharArray(), hint, s.imported, withKey); pw = ""; confirm = ""
        }
        return
    }
    Page("Create vault", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        BrandHeader("Step 1 of 3: master password")
        PanelCard {
            s.imported?.let { Muted("${it.activeEntries.size} entries from the desktop file will be in the new vault.") }
            val ok = newPasswordFields(vm, pw, { pw = it }, confirm, { confirm = it }, hint, { hint = it })
            MainButton("Next", { keyStep = true }, Modifier.fillMaxWidth().testTag("createSubmit"), enabled = ok)
        }
    }
}

/** Create flow, step 2: the recovery key is optional (owner's choice, 2026-11-02), but recommended. */
@Composable
private fun RecoveryKeyStep(vm: AppViewModel, snackbar: SnackbarHostState, onBack: () -> Unit, create: (withKey: Boolean) -> Unit) {
    var confirmSkip by remember { mutableStateOf(false) }
    Page("Create vault", snackbar, onBack = onBack, busy = vm.busy) {
        BrandHeader("Step 2 of 3: recovery key")
        PanelCard(title = "Recovery key") {
            Text(buildAnnotatedString {
                append("A recovery key is a 36-character code you print or write down. If you forget your master password, it is the ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Palette.orange)) { append("only") }
                append(" way back in. Server backups won't help: they are locked with the same password.")
            }, color = Palette.fgPrimary, modifier = Modifier.testTag("recoveryChoice"))
            MainButton("Create recovery key (recommended)", { create(true) }, Modifier.fillMaxWidth().testTag("withKey"),
                Icons.Filled.Key, enabled = !vm.busy)
            OutlineButton("Skip", { confirmSkip = true }, Palette.fgPrimary, Modifier.fillMaxWidth().testTag("skipKey"),
                borderColor = Palette.fgSecondary, enabled = !vm.busy)
        }
    }
    if (confirmSkip) ConfirmDialog(
        "Skip the recovery key?",
        "Without a recovery key, a forgotten master password means all passwords in this vault are lost. Skip anyway?",
        confirm = "Skip anyway", confirmColor = Palette.red, dismiss = "Back", confirmTag = "skipConfirm",
        onConfirm = { confirmSkip = false; create(false) }, onDismiss = { confirmSkip = false },
    )
}

@Composable
private fun KitScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val k = vm.kit ?: return
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        vm.externalDone()
        if (uri != null) vm.writeKitPdf(uri)
    }
    Page("Emergency Kit", snackbar, busy = vm.busy) {
        PanelCard(title = "Your recovery key") {
            Text("It opens the vault if you forget the master password.", color = Palette.fgPrimary)
            Box(Modifier.fillMaxWidth().background(Palette.bgInput, RoundedCornerShape(10.dp)).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    k.groups.chunked(3).joinToString("\n") { it.joinToString("-") },
                    fontFamily = FontFamily.Monospace, fontSize = 22.sp, lineHeight = 32.sp, color = Palette.orange,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("recoveryKey"),
                )
            }
            Muted("Vault ID: ${k.vaultId}")
            WarnText("Write it on paper or save the PDF and print it. Keep it offline. It is shown only now.")
            ColorButton("Save Emergency Kit as PDF", { vm.externalStarted(); savePdf.launch("MobileVault-EmergencyKit.pdf") },
                Palette.steel, Modifier.fillMaxWidth().testTag("kitPdf"), Icons.Filled.PictureAsPdf, enabled = !vm.busy)
            MainButton("I wrote it down", { vm.kitWrittenDown() }, Modifier.fillMaxWidth().testTag("kitDone"), Icons.Filled.Check,
                enabled = !vm.busy)
            Muted("Saving the PDF also completes this step. You can check the key later in Settings → Security.")
        }
    }
}

// ── unlock & recovery ────────────────────────────────────────────────────────
@Composable
private fun UnlockScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by rememberSecret()
    var hintShown by remember { mutableStateOf(false) }
    val submit = { if (pw.isNotEmpty()) { vm.unlock(pw.toCharArray()); pw = "" } }
    val activity = LocalContext.current as FragmentActivity
    // The fingerprint prompt opens by itself once when the policy allows it; the password field is always there.
    LaunchedEffect(vm.biometricAllowed) {
        if (vm.biometricAllowed && !vm.bioAutoOffered) { vm.bioAutoOffered = true; offerBiometricUnlock(vm, activity) }
    }
    Page("", snackbar, busy = vm.busy) {
        BrandHeader("Vault locked")
        PanelCard {
            vm.biometricReason?.let { WarnText(it, "bioReason") }
            PasswordField(pw, { pw = it }, "Master password", tag = "unlockPw", imeAction = ImeAction.Go, onDone = submit,
                isError = vm.unlockError != null)
            vm.unlockError?.let { ErrorText(it) }
            if (vm.waitSeconds > 0) WarnText("Too many attempts. Try again in ${vm.waitSeconds} s.")
            MainButton("Unlock", submit, Modifier.fillMaxWidth().testTag("unlock"), Icons.Filled.LockOpen,
                enabled = !vm.busy && vm.waitSeconds == 0 && pw.isNotEmpty())
            if (vm.biometricAllowed) BiometricUnlockButton(vm)
            if (vm.showHintButton && vm.hint != null) {
                if (hintShown) WarnText("Hint: ${vm.hint}")
                else TextButton(onClick = { hintShown = true }) { Text("Show hint", color = Palette.navFg) }
            }
        }
        // Only when the file has a recovery slot: without one there is nothing to offer (no dead end).
        if (vm.hasRecoveryKey) TextButton(
            onClick = { vm.recoveryError = null; vm.go(Screen.Recovery) },
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("toRecovery"),
        ) { Text("Forgot password? Use recovery key", color = Palette.navFg) }
    }
}

@Composable
private fun RecoveryScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    // Kept while the app is locked in the background: nothing is open on this screen (see AppViewModel.onLocked).
    var key by rememberSecret()
    val submit = { if (key.isNotBlank() && !vm.busy) vm.unlockWithRecoveryKey(key) }
    Page("Recovery key", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("Type or paste the 36-character key from your Emergency Kit. Dashes, spaces and case don't matter.", color = Palette.fgPrimary)
            vm.vaultId?.let { Muted("Vault ID: $it (the same ID is printed on the kit)") }
            KeyField(key, { key = it; vm.recoveryError = null }, "Recovery key", tag = "recoveryInput",
                isError = vm.recoveryError != null, imeAction = ImeAction.Go, onDone = submit)
            val n = key.count { it.isLetterOrDigit() }
            vm.recoveryError?.let { ErrorText(it, "recoveryError") } ?: Muted("$n of 36 characters")
            if (vm.busy) CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.CenterHorizontally), color = Palette.orange)
            else MainButton("Unlock", submit, Modifier.fillMaxWidth().testTag("recoveryUnlock"), Icons.Filled.LockOpen, enabled = key.isNotBlank())
            Muted("After unlocking you will set a new master password.")
        }
    }
}

/** Settings → Security → "Check my recovery key": only checks, changes nothing, no master password needed. */
@Composable
private fun CheckRecoveryKeyScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var key by rememberSecret()
    val submit = { if (key.isNotBlank() && !vm.busy) vm.checkRecoveryKey(key) }
    Page("Check my recovery key", snackbar, onBack = { key = ""; vm.resetKeyCheck(); vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("Type or paste the key from your Emergency Kit to make sure it still opens this vault. Nothing is changed.",
                color = Palette.fgPrimary)
            KeyField(key, { key = it; vm.resetKeyCheck() }, "Recovery key", tag = "checkKeyInput", imeAction = ImeAction.Go, onDone = submit)
            Muted("${key.count { it.isLetterOrDigit() }} of 36 characters")
            MainButton("Check", submit, Modifier.fillMaxWidth().testTag("checkKeyRun"), Icons.Filled.Key, enabled = key.isNotBlank() && !vm.busy)
            when (vm.keyCheckOk) {
                true -> Text(vm.keyCheckText ?: "", color = Palette.green, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("checkKeyResult"))
                false -> ErrorText(vm.keyCheckText ?: "", "checkKeyResult")
                null -> {}
            }
        }
    }
}

@Composable
private fun NewPasswordScreen(vm: AppViewModel, snackbar: SnackbarHostState, forced: Boolean, withoutCurrent: Boolean = false) {
    var current by rememberSecret()
    var pw by rememberSecret()
    var confirm by rememberSecret()
    var hint by remember { mutableStateOf("") }
    var rotate by remember { mutableStateOf(false) }
    Page(if (forced) "Set a new master password" else "Change master password", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            if (forced) Text("You unlocked with the recovery key. Choose a new master password; the recovery key stays valid.", color = Palette.fgPrimary)
            else if (withoutCurrent) Text("The vault is open, so you can set a new master password now, before the old one is lost.", color = Palette.fgPrimary)
            else PasswordField(current, { current = it }, "Current master password", tag = "currentPw")
            val ok = newPasswordFields(vm, pw, { pw = it }, confirm, { confirm = it }, hint, { hint = it })
            if (!forced) {
                // after a recovery-key unlock the kit was used, not lost: no rotation needed there
                CheckRow("Someone may know my old password", rotate, Modifier.testTag("rotateDek")) { rotate = it }
                Muted("Also changes the vault key, so newer versions don't open with the old password (older backups still do)." +
                    if (vm.hasRecoveryKey) " You get a new recovery key and Emergency Kit." else "")
            }
            MainButton("Save", {
                vm.setNewPassword(if (forced || withoutCurrent) null else current.toCharArray(), pw.toCharArray(), hint, rotate = rotate && !forced)
                current = ""; pw = ""; confirm = ""
            }, Modifier.fillMaxWidth().testTag("newPwSave"), Icons.Filled.Save, enabled = ok && (forced || withoutCurrent || current.isNotEmpty()))
        }
    }
}

// ── list, detail, edit ───────────────────────────────────────────────────────
@Composable
private fun ListScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val payload by vm.payload.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(VaultEdits.Sort.AZ) }
    val sections = payload?.let { VaultEdits.sections(it) }.orEmpty()
    // a remembered chip whose section was deleted meanwhile falls back to "All"
    val filter = vm.listSection.let { f -> if (f is SectionFilter.Only && sections.none { it.id == f.id }) SectionFilter.All else f }
    val entries = payload?.let { VaultEdits.visible(it, query, sort, filter) }.orEmpty()
    val showSectionTag = sections.isNotEmpty() && (filter == SectionFilter.All || query.isNotBlank())
    val sectionIds = sections.map { it.id }.toSet()
    var menu by remember { mutableStateOf(false) }
    var split by remember { mutableStateOf(false) }
    val total = payload?.activeEntries?.size ?: 0
    Page(
        "Mobile Vault", snackbar, busy = vm.busy, scroll = false, titleColor = Palette.orange,
        actions = {
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings", tint = Palette.fgSecondary) }
            IconButton(onClick = { vm.lockNow() }, Modifier.testTag("lock")) { Icon(Icons.Filled.Lock, "Lock", tint = Palette.red) }
            Box {
                IconButton(onClick = { menu = true }, Modifier.testTag("listMenu")) { Icon(Icons.Filled.MoreVert, "More", tint = Palette.fgSecondary) }
                DropdownMenu(menu, { menu = false }) {
                    if (sections.isEmpty()) DropdownMenuItem(text = { Text("Split into sections (e.g. Personal / Work)") },
                        onClick = { menu = false; split = true }, modifier = Modifier.testTag("menuSplit"))
                    else DropdownMenuItem(text = { Text("Sections…") }, onClick = { menu = false; vm.go(Screen.Sections) },
                        modifier = Modifier.testTag("menuSections"))
                }
            }
        },
        floating = {
            FloatingActionButton(onClick = { vm.go(Screen.Edit(null)) }, containerColor = Palette.green, contentColor = Color.White,
                modifier = Modifier.testTag("add")) { Icon(Icons.Filled.Add, "Add") }
        },
        bottomBar = {
            // Status line like the desktop's: entry count and the crypto in use.
            Text(
                "$total ${if (total == 1) "entry" else "entries"}  •  AES-256-GCM  •  Argon2id",
                color = Palette.fgMuted, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().background(Palette.bgStatus).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        },
    ) {
        PlainField(query, { query = it }, if (sections.isEmpty()) "Search service, username or URL" else "Search all sections",
            tag = "search", leading = Icons.Filled.Search)
        if (sections.isNotEmpty()) payload?.let { SectionChips(vm, it, sections, filter) }
        ChoiceRow("", VaultEdits.Sort.entries, sort, { if (it == VaultEdits.Sort.AZ) "A–Z" else "Recently modified" }) { sort = it }
        BackupChip(vm)
        TestRestoreCard(vm)
        PracticePasswordCard(vm)
        KitCheckCard(vm)
        if (vm.kitPending) KitPendingCard(vm)
        if (entries.isEmpty()) Muted(if (query.isEmpty()) "No entries yet. Tap + to add one." else "Nothing found.")
        LazyColumn(Modifier.background(Palette.bgPanel, RoundedCornerShape(12.dp))) {
            items(entries, key = { it.id }) { e ->
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (pressed) Palette.selectBg else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable(interactionSource = source, indication = null) { vm.go(Screen.Detail(e.id)) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("entry:${e.service}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Initial(e.service)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.service, color = Palette.fgPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.username.isNotEmpty()) Text(e.username, color = Palette.fgSecondary, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (showSectionTag) SectionTag(sectionLabel(sections, if (e.section in sectionIds) e.section else ""))
                    }
                    if (e.favourite) Icon(Icons.Filled.Star, "Favourite", tint = Palette.orange)
                }
                if (e != entries.last()) HorizontalDivider(Modifier.padding(start = 64.dp), color = Palette.sep, thickness = 0.5.dp)
            }
        }
    }
    if (split) SplitIntoSectionsDialog(vm, total) { split = false }
}

/** The vault has a recovery key whose Emergency Kit was never confirmed (the app locked on that screen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitPendingCard(vm: AppViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    PanelCard(Modifier.testTag("kitPending"), title = "Recovery key not confirmed") {
        Muted("You didn't save or write down your recovery key. Make a new one, or remove it.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorButton("New recovery key", { confirm = true }, Palette.blue, icon = Icons.Filled.Key)
            OutlineButton("Remove recovery key", { remove = true }, Palette.red, Modifier.testTag("kitPendingRemove"), Icons.Filled.Delete)
        }
    }
    if (confirm) NewRecoveryKeyDialog(vm) { confirm = false }
    if (remove) RemoveRecoveryKeyDialogs(vm) { remove = false }
}

/** "New recovery key" / "Create recovery key": the master password is needed to re-wrap the new vault key (DEK rotation). */
@Composable
internal fun NewRecoveryKeyDialog(vm: AppViewModel, adding: Boolean = false, onDone: () -> Unit) {
    var pw by rememberSecret()
    ConfirmDialog(
        if (adding) "Create recovery key" else "New recovery key?", KeyChanges.NEW_KEY_CONFIRM,
        confirm = "Continue", confirmTag = "newKeyOk", confirmEnabled = pw.isNotEmpty() && !vm.busy,
        onConfirm = { vm.newRecoveryKey(pw.toCharArray(), onDone); pw = "" }, onDismiss = onDone,
    ) { PasswordField(pw, { pw = it }, "Master password", tag = "newKeyPw") }
}

/** "Remove recovery key": master password first, then a confirmation, then the slot is dropped and saved. */
@Composable
private fun RemoveRecoveryKeyDialogs(vm: AppViewModel, onDone: () -> Unit) {
    var pw by rememberSecret()
    // the checked password is kept for step 2: it re-wraps the new vault key
    var checked by remember { mutableStateOf<String?>(null) }
    val ok = checked
    if (ok == null) ConfirmDialog(
        "Remove recovery key", "Enter your master password to continue.",
        confirm = "Continue", confirmTag = "removePwOk", confirmEnabled = pw.isNotEmpty() && !vm.busy,
        onConfirm = { val typed = pw; pw = ""; vm.checkPasswordForRemoval(typed.toCharArray()) { checked = typed } }, onDismiss = onDone,
    ) { PasswordField(pw, { pw = it }, "Master password", tag = "removePw") }
    else ConfirmDialog(
        "Remove the recovery key?", KeyChanges.REMOVE_CONFIRM,
        confirm = "Remove", confirmColor = Palette.red, confirmTag = "removeConfirm",
        onConfirm = { checked = null; onDone(); vm.removeRecoveryKey(ok.toCharArray()) }, onDismiss = { checked = null; onDone() },
    )
}

@Composable
private fun DetailScreen(vm: AppViewModel, snackbar: SnackbarHostState, id: String) {
    val payload by vm.payload.collectAsState()
    val e = payload?.entries?.firstOrNull { it.id == id && !it.deleted } ?: return
    var show by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // No title in the top bar: the name is shown large next to the coloured initial below.
    Page("", snackbar, onBack = { vm.back() }, busy = vm.busy, actions = {
        IconButton(onClick = { vm.toggleFavourite(e) }) {
            Icon(if (e.favourite) Icons.Filled.Star else Icons.Filled.StarBorder, if (e.favourite) "Unfavourite" else "Favourite",
                tint = if (e.favourite) Palette.orange else Palette.fgSecondary)
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Initial(e.service); Spacer(Modifier.width(12.dp))
            Text(e.service, color = Palette.fgPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
        DetailCard("Username", e.username) {
            ColorButton("Copy username", { vm.copy("Username", e.username) }, Palette.teal, Modifier.testTag("copy:Username"), Icons.Filled.ContentCopy)
        }
        DetailCard("Password", if (show) e.password else "••••••••••", mono = true) {
            ColorButton(if (show) "Hide" else "Show", { show = !show }, Palette.amber,
                icon = if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility)
            ColorButton("Copy password", { vm.copy("Password", e.password) }, Palette.steel, Modifier.testTag("copy:Password"), Icons.Filled.ContentCopy)
        }
        DetailCard("URL", e.url) {
            ColorButton("Open", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, e.url.toUri())) } },
                Palette.purple, Modifier.testTag("copy:URL"), Icons.AutoMirrored.Filled.OpenInNew)
            ColorButton("Copy URL", { vm.copy("URL", e.url) }, Palette.purple, icon = Icons.Filled.ContentCopy)
        }
        if (e.notes.isNotEmpty()) PanelCard {
            Text("Notes", color = Palette.fgSecondary, style = MaterialTheme.typography.labelMedium)
            Text(e.notes, color = Palette.fgPrimary)
        }
        payload?.let { p -> VaultEdits.sections(p).takeIf { it.isNotEmpty() }?.let { s ->
            Muted("Section: ${sectionLabel(s, VaultEdits.effectiveSection(p, e))}")
        } }
        Muted("Modified ${localTime(e.modified)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorButton("Edit", { vm.go(Screen.Edit(e.id)) }, Palette.blue, Modifier.weight(1f).testTag("editEntry"), Icons.Filled.Edit)
            ColorButton("Delete", { confirmDelete = true }, Palette.red, Modifier.weight(1f), Icons.Filled.Delete)
        }
    }
    if (confirmDelete) ConfirmDialog(
        "Delete ${e.service}?", "The entry is removed from the list. Older copies stay in local snapshots.",
        confirm = "Delete", confirmColor = Palette.red,
        onConfirm = { confirmDelete = false; vm.deleteEntry(e.id) }, onDismiss = { confirmDelete = false },
    )
}

/** One field of an entry: label, value and its coloured action buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailCard(label: String, value: String, mono: Boolean = false, buttons: @Composable () -> Unit) {
    if (value.isEmpty()) return
    PanelCard {
        Text(label, color = Palette.fgSecondary, style = MaterialTheme.typography.labelMedium)
        Text(value, color = Palette.fgPrimary, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

@Composable
private fun EditScreen(vm: AppViewModel, snackbar: SnackbarHostState, id: String?) {
    val payload by vm.payload.collectAsState()
    val orig = remember(id) { id?.let { i -> payload?.entries?.firstOrNull { it.id == i } } }
    var service by remember { mutableStateOf(orig?.service ?: "") }
    var username by remember { mutableStateOf(orig?.username ?: "") }
    var password by remember { mutableStateOf(orig?.password ?: "") }
    var url by remember { mutableStateOf(orig?.url ?: "") }
    var notes by remember { mutableStateOf(orig?.notes ?: "") }
    var fav by remember { mutableStateOf(orig?.favourite ?: false) }
    val sections = payload?.let { VaultEdits.sections(it) }.orEmpty()
    // a new entry starts in the section selected on the list (or none under "All")
    var section by remember(id) {
        mutableStateOf(orig?.let { o -> payload?.let { VaultEdits.effectiveSection(it, o) } } ?: (vm.listSection as? SectionFilter.Only)?.id
            ?.takeIf { sid -> sections.any { it.id == sid } } ?: "")
    }
    var showGen by remember { mutableStateOf(false) }
    Page(if (orig == null) "New entry" else "Edit ${orig.service}", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            PlainField(service, { service = it }, "Service *", tag = "service")
            PlainField(username, { username = it }, "Username", tag = "username", keyboardType = KeyboardType.Email)
            PasswordField(password, { password = it }, "Password *", tag = "password", mono = true)
            StrengthMeter(password)
            if (showGen) GeneratorPanel(vm.generator) { password = it }
            else MainButton("Generate password", { showGen = true }, Modifier.fillMaxWidth(), Icons.Filled.AutoAwesome)
            PlainField(url, { url = it }, "URL", keyboardType = KeyboardType.Uri)
            PlainField(notes, { notes = it }, "Notes", singleLine = false)
            if (sections.isNotEmpty()) SectionPicker("Section", sections, section, { section = it }, tag = "entrySection")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(fav, { fav = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.orange, checkedThumbColor = Palette.onOrange))
                Spacer(Modifier.width(10.dp)); Text("Favourite", color = Palette.fgPrimary)
            }
        }
        MainButton("Save", {
            val base = orig ?: Entry(service = service.trim(), password = password)
            vm.saveEntry(base.copy(service = service.trim(), username = username.trim(), password = password,
                url = url.trim(), notes = notes, favourite = fav, section = section))
        }, Modifier.fillMaxWidth().testTag("save"), Icons.Filled.Save, enabled = service.isNotBlank() && password.isNotEmpty() && !vm.busy)
    }
}

@Composable
private fun GeneratorPanel(gen: PasswordGenerator, onGenerated: (String) -> Unit) {
    var o by remember { mutableStateOf(PasswordGenerator.Options()) }
    val sliderColors = SliderDefaults.colors(thumbColor = Palette.orange, activeTrackColor = Palette.orange, inactiveTrackColor = Palette.bgHover)
    Column(
        Modifier.fillMaxWidth().background(Palette.bgInput, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Password generator", color = Palette.orange, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(o.passphrase, { o = o.copy(passphrase = it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.orange, checkedThumbColor = Palette.onOrange))
            Spacer(Modifier.width(10.dp)); Text("Passphrase (words)", color = Palette.fgPrimary)
        }
        if (o.passphrase) {
            Text("Words: ${o.words}", color = Palette.fgPrimary)
            Slider(o.words.toFloat(), { o = o.copy(words = it.roundToInt()) }, colors = sliderColors,
                valueRange = PasswordGenerator.MIN_WORDS.toFloat()..PasswordGenerator.MAX_WORDS.toFloat(),
                steps = PasswordGenerator.MAX_WORDS - PasswordGenerator.MIN_WORDS - 1)
        } else {
            Text("Length: ${o.length}", color = Palette.fgPrimary)
            Slider(o.length.toFloat(), { o = o.copy(length = it.roundToInt()) }, colors = sliderColors,
                valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat())
            CheckRow("a–z", o.lower) { o = o.copy(lower = it) }
            CheckRow("A–Z", o.upper) { o = o.copy(upper = it) }
            CheckRow("0–9", o.digits) { o = o.copy(digits = it) }
            CheckRow("Symbols", o.symbols) { o = o.copy(symbols = it) }
        }
        MainButton("Generate", { onGenerated(gen.generate(o)) }, Modifier.fillMaxWidth(), Icons.Filled.AutoAwesome)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Palette.orange, checkmarkColor = Palette.onOrange))
        Text(label, color = Palette.fgPrimary)
    }
}

// ── settings, import, export, about ──────────────────────────────────────────
@Composable
private fun SettingsScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val s by vm.settings.collectAsState()
    var confirmRegen by remember { mutableStateOf(false) }
    var addKey by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val pickImport = rememberImportPicker(vm)
    val saveFullCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.externalDone()
        if (uri != null) vm.saveFullCopy(uri)
    }
    Page("Settings", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard(title = "Security") {
            ChoiceRow("Auto-lock after", Settings.AUTO_LOCK_CHOICES, s.autoLockMinutes, { "$it min" }) {
                vm.updateSettings(s.copy(autoLockMinutes = it))
            }
            ChoiceRow("Lock when leaving the app", Settings.GRACE_CHOICES, s.backgroundGraceSeconds,
                { if (it == 0) "Immediately" else if (it == 30) "After 30 s" else "After 1 min" }) {
                vm.updateSettings(s.copy(backgroundGraceSeconds = it))
            }
            ChoiceRow("Clear copied passwords after", Settings.CLIPBOARD_CHOICES, s.clipboardClearSeconds,
                { if (it == 0) "Never" else "$it s" }) { vm.updateSettings(s.copy(clipboardClearSeconds = it)) }
            BiometricSettings(vm)
            if (vm.hasRecoveryKey) ColorButton("Check my recovery key", { vm.resetKeyCheck(); vm.go(Screen.CheckRecoveryKey) }, Palette.steel,
                Modifier.fillMaxWidth().testTag("checkKey"), Icons.Filled.Key)
            ColorButton("Change master password", { vm.go(Screen.NewPassword(forced = false)) }, Palette.blue,
                Modifier.fillMaxWidth(), Icons.Filled.Password)
        }
        PanelCard(title = "Recovery key") {
            if (vm.hasRecoveryKey) {
                Muted("Opens the vault if you forget the master password.")
                ColorButton("New recovery key", { confirmRegen = true }, Palette.blue, Modifier.fillMaxWidth().testTag("newRecoveryKey"), Icons.Filled.Key)
                OutlineButton("Remove recovery key", { remove = true }, Palette.red, Modifier.fillMaxWidth().testTag("removeRecoveryKey"), Icons.Filled.Delete)
            } else {
                WarnText("No recovery key. If you forget the master password, the vault is lost.", "noRecoveryKey")
                ColorButton("Create recovery key", { addKey = true }, Palette.green, Modifier.fillMaxWidth().testTag("addRecoveryKey"), Icons.Filled.Key)
            }
        }
        PanelCard(title = "Sections") {
            val n = vm.payload.collectAsState().value?.let { VaultEdits.sections(it).size } ?: 0
            Muted(if (n == 0) "Split this vault into sections, e.g. Personal and Work." else "$n sections.")
            ColorButton("Sections", { vm.go(Screen.Sections) }, Palette.steel, Modifier.fillMaxWidth().testTag("toSections"), Icons.Filled.Folder)
        }
        PanelCard(title = "Backup") {
            BackupSummary(vm)
            ColorButton("Backup settings", { vm.go(Screen.Backup) }, Palette.steel, Modifier.fillMaxWidth().testTag("toBackup"), Icons.Filled.CloudUpload)
        }
        PanelCard(title = "Import / Export") {
            Muted("Import another vault into this one, e.g. into its own section.")
            ColorButton("Import a file (desktop or Mobile Vault)", pickImport, Palette.violet, Modifier.fillMaxWidth().testTag("importFile"),
                Icons.Filled.FileDownload)
            val paired = vm.backupConfig.collectAsState().value != null
            ColorButton("Import from the backup server", { vm.openImportFromServer() }, Palette.violet,
                Modifier.fillMaxWidth().testTag("importServer"), Icons.Filled.CloudDownload, enabled = paired && !vm.busy)
            ColorButton("Export for desktop", { vm.go(Screen.Export) }, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.FileUpload)
            ColorButton("Save a full copy", { vm.externalStarted(); saveFullCopy.launch(vm.fullCopyName()) }, Palette.violet,
                Modifier.fillMaxWidth().testTag("fullCopy"), Icons.Filled.Save, enabled = !vm.busy)
            Muted("A full copy is the encrypted vault file with everything in it (sections, notes, tags, favourites). " +
                "It opens with your master password (or your recovery key), also without the backup server: Welcome screen → " +
                "Restore from a file. Keep it on a USB stick or another safe place. Anyone needs your master password to open it.")
        }
        if (BuildConfig.DEBUG) DebugClockCard(vm)
        PanelCard(title = "About") {
            SecondaryButton("About Mobile Vault", { vm.go(Screen.About) }, Modifier.fillMaxWidth(), Icons.Filled.Info)
        }
    }
    if (confirmRegen) NewRecoveryKeyDialog(vm) { confirmRegen = false }
    if (addKey) NewRecoveryKeyDialog(vm, adding = true) { addKey = false }
    if (remove) RemoveRecoveryKeyDialogs(vm) { remove = false }
}

@Composable
private fun ImportPasswordScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by rememberSecret()
    var key by rememberSecret()
    var useKey by remember { mutableStateOf(false) }
    val h = vm.importHeader
    val submit = {
        if (!vm.busy) { if (useKey) vm.importDecrypt(null, key) else if (pw.isNotEmpty()) vm.importDecrypt(pw.toCharArray()); pw = "" }
    }
    Page(if (vm.importIsMobile) "Import a Mobile Vault" else "Import from desktop", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            if (!vm.importIsMobile) {
                Muted("Enter the password of the desktop vault file.")
                PasswordField(pw, { pw = it; vm.importError = null }, "Desktop vault password", tag = "importPw", imeAction = ImeAction.Go,
                    onDone = submit, isError = vm.importError != null)
            } else {
                Muted("This is another Mobile Vault (saved ${h?.updated?.let { localTime(it) } ?: "?"}). It is opened in memory only, " +
                    "with ITS master password or recovery key, and is not changed.")
                if (!useKey) {
                    PasswordField(pw, { pw = it; vm.importError = null }, "Master password of that vault", tag = "importPw",
                        imeAction = ImeAction.Go, onDone = submit, isError = vm.importError != null)
                    h?.hint?.let { Muted("Hint: $it") }
                } else KeyField(key, { key = it; vm.importError = null }, "Recovery key of that vault", tag = "importKey")
                if (h?.hasRecoveryKey == true) TextButton(onClick = { useKey = !useKey }) {
                    Text(if (useKey) "Use its master password instead" else "Use its recovery key instead", color = Palette.navFg)
                }
            }
            vm.importError?.let { ErrorText(it, "importError") }
            MainButton("Open", submit, Modifier.fillMaxWidth().testTag("importOpen"), Icons.Filled.FolderOpen,
                enabled = (if (useKey) key.isNotBlank() else pw.isNotEmpty()) && !vm.busy)
        }
    }
}

/** Import from the backup server, step 1: another vault (the open one is not listed). */
@Composable
private fun ImportServerScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    Page("Import from the server", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        if (vm.importVaults.isEmpty()) WarnText("The server has no other vaults.")
        else Muted("Other vaults backed up on your server. Pick the one to import.")
        vm.importVaults.forEach { v -> VaultRow(v) { vm.pickImportVault(v.vaultId) } }
    }
}

/** Import from the backup server, step 2: a revision (the newest is usually right). */
@Composable
private fun ImportServerRevsScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    Page("Choose a version", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        Muted("The newest version is usually the right one.")
        vm.remoteRevs.forEachIndexed { i, r -> RevisionRow(r, i == 0) { vm.pickImportRevision(r.rev) } }
    }
}

@Composable
private fun ImportPreviewScreen(vm: AppViewModel, snackbar: SnackbarHostState, count: Int) {
    val payload by vm.payload.collectAsState()
    val unlocked = payload != null
    val sections = payload?.let { VaultEdits.sections(it) }.orEmpty()
    // default target (Task 11): the existing section named like the file ("Personal") if there is one; else a new section
    // with that name; "No section" when neither vault uses sections
    var target by remember { mutableStateOf(ImportInto.defaultTarget(sections, vm.importName, vm.importSourceSections) ?: NEW_SECTION) }
    var newName by remember { mutableStateOf(vm.importName) }
    var keep by remember { mutableStateOf(true) }
    Page("Import", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("The file contains $count entries.", color = Palette.fgPrimary, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("importCount"))
            if (unlocked) {
                if (vm.importSourceSections > 0) {
                    Muted("That vault has ${vm.importSourceSections} sections of its own.")
                    Row(Modifier.fillMaxWidth().clickable { keep = true }.testTag("importKeep"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(keep, { keep = true }, colors = RadioButtonDefaults.colors(selectedColor = Palette.orange))
                        Text("Keep its sections", color = Palette.fgPrimary)
                    }
                    Row(Modifier.fillMaxWidth().clickable { keep = false }.testTag("importOneSection"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!keep, { keep = false }, colors = RadioButtonDefaults.colors(selectedColor = Palette.orange))
                        Text("Put everything into one section", color = Palette.fgPrimary)
                    }
                }
                SectionPicker(if (vm.importSourceSections > 0 && keep) "Entries without a section go into:" else "Import into section:",
                    sections, target, { target = it }, tag = "importTarget", extra = listOf(NEW_SECTION to "New section…"))
                val nameProblem = if (target == NEW_SECTION) ImportInto.newSectionProblem(sections, newName) else null
                if (target == NEW_SECTION) {
                    PlainField(newName, { newName = it; vm.importError = null }, "Name of the new section", tag = "importNewName",
                        isError = nameProblem != null)
                    nameProblem?.let { ErrorText(it, "importNameError") }
                }
                Muted("Entries that already exist in that section with the same service, username and password are skipped. " +
                    "The source is not changed.")
                ColorButton("Import", {
                    val t = when (target) {
                        NEW_SECTION -> ImportInto.Target.New(newName)
                        "" -> ImportInto.Target.NoSection
                        else -> ImportInto.Target.Existing(target)
                    }
                    vm.importMerge(t, keepSections = keep)
                }, Palette.violet, Modifier.fillMaxWidth().testTag("importMerge"), Icons.Filled.FileDownload,
                    enabled = !vm.busy && nameProblem == null)
                vm.importError?.let { ErrorText(it, "importError") }
            } else {
                ColorButton("Create a new vault with them", { vm.importAsNewVault() }, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.Add,
                    enabled = !vm.busy)
            }
        }
    }
}

@Composable
private fun ExportScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by rememberSecret()
    var confirm by rememberSecret()
    val payload by vm.payload.collectAsState()
    val sections = payload?.let { VaultEdits.sections(it) }.orEmpty()
    var which by remember { mutableStateOf(ALL_ENTRIES) }                 // ALL_ENTRIES, "" = No section, or a section id
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.externalDone()
        if (uri != null) { vm.exportTo(uri, pw.toCharArray(), which.takeIf { it != ALL_ENTRIES }); pw = ""; confirm = "" }
    }
    val fileName = if (which == ALL_ENTRIES) "mobilevault-export.vault"
        else "mobilevault-" + sectionLabel(sections, which).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".vault"
    Page("Export for desktop", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("Creates a file the desktop app (Shu PassVault) can open.", color = Palette.fgPrimary)
            WarnText("Notes, tags and favourites are not part of the desktop format and are left out.")
            if (sections.isNotEmpty()) {
                SectionPicker("Export", sections, which, { which = it }, tag = "exportWhich", extra = listOf(ALL_ENTRIES to "All entries"))
                Muted("The desktop app has no sections: exporting one section gives a separate desktop file.")
            }
            Muted("Choose a password for the exported file. Anyone with the file and this password can read all entries.")
            PasswordField(pw, { pw = it }, "Export password")
            StrengthMeter(pw)
            PasswordField(confirm, { confirm = it }, "Repeat export password", isError = confirm.isNotEmpty() && confirm != pw)
            MainButton("Choose where to save", { vm.externalStarted(); save.launch(fileName) },
                Modifier.fillMaxWidth(), Icons.Filled.Save, enabled = pw.length >= MIN_EXPORT_LENGTH && pw == confirm && !vm.busy)
        }
    }
}

@Composable
private fun AboutScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    Page("About", snackbar, onBack = { vm.back() }) {
        BrandHeader("Version ${BuildConfig.VERSION_NAME}")
        PanelCard {
            Muted("Licensed under the GNU General Public License v3.0 or later.")
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/spirtosan/mobilevault".toUri())) }
            }) { Text("github.com/spirtosan/mobilevault", color = Palette.navFg) }
            Muted("Passphrases use the EFF Short Wordlist 1 by the Electronic Frontier Foundation (CC BY 3.0 US).")
            Muted("Encryption: AES-256-GCM, Argon2id (own Kotlin implementation, RFC 9106), recovery key via HKDF-SHA256.")
        }
    }
}
