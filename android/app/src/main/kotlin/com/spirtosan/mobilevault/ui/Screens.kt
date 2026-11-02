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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
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
import com.spirtosan.mobilevault.BuildConfig
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.data.Settings
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.security.MasterPasswordPolicy
import com.spirtosan.mobilevault.security.PasswordGenerator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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
        Screen.Unlock -> UnlockScreen(vm, snackbar)
        Screen.Recovery -> RecoveryScreen(vm, snackbar)
        is Screen.NewPassword -> NewPasswordScreen(vm, snackbar, s.forced)
        Screen.VaultList -> ListScreen(vm, snackbar)
        is Screen.Detail -> DetailScreen(vm, snackbar, s.id)
        is Screen.Edit -> EditScreen(vm, snackbar, s.id)
        Screen.Settings -> SettingsScreen(vm, snackbar)
        Screen.ImportPassword -> ImportPasswordScreen(vm, snackbar)
        is Screen.ImportPreview -> ImportPreviewScreen(vm, snackbar, s.count)
        Screen.Export -> ExportScreen(vm, snackbar)
        Screen.About -> AboutScreen(vm, snackbar)
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
private fun ErrorText(text: String, tag: String? = null) =
    Text(text, color = Palette.red, style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)

@Composable
private fun WarnText(text: String, tag: String? = null) =
    Text(text, color = Palette.orange, style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)

// ── onboarding & create ──────────────────────────────────────────────────────
@Composable
private fun OnboardingScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val pickImport = rememberImportPicker(vm)
    Page("", snackbar, busy = vm.busy) {
        BrandHeader("Your passwords, encrypted on this phone.")
        PanelCard {
            Muted("Nothing leaves the phone unless you export it. Backups to your own server come in a later version.")
            MainButton("Create new vault", { vm.go(Screen.Create(null)) }, Modifier.fillMaxWidth().testTag("create"), Icons.Filled.Add)
            ColorButton("Import from desktop (.vault)", pickImport, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.FolderOpen)
            SecondaryButton("Restore from server (coming soon)", {}, Modifier.fillMaxWidth(), enabled = false)
        }
    }
}

@Composable
private fun CalibrationInfo(vm: AppViewModel) {
    val c = vm.calibration
    if (c == null) Muted("Measuring how fast this phone unlocks…")
    else Muted("Unlock takes ~%.1f s on this phone (Argon2id %d MiB, t=%d, p=%d)".format(
        c.unlockMs / 1000.0, c.params.mKiB / 1024, c.params.t, c.params.p))
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
    var pw by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf("") }
    var keyStep by rememberSaveable { mutableStateOf(false) }      // step 2: recovery key or skip
    if (keyStep) {
        BackHandler { keyStep = false }
        RecoveryKeyStep(vm, snackbar, onBack = { keyStep = false }) { withKey ->
            vm.createVault(pw.toCharArray(), hint, s.imported, withKey); pw = ""; confirm = ""
        }
        return
    }
    Page("Create vault", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        BrandHeader("Step 1 of 2: master password")
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
        BrandHeader("Step 2 of 2: recovery key")
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
    var typed by remember(k) { mutableStateOf(List(3) { "" }) }
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
                Palette.steel, Modifier.fillMaxWidth(), Icons.Filled.PictureAsPdf)
        }
        PanelCard(title = "Check that you have it") {
            Text("Type these groups:", color = Palette.fgPrimary)
            k.challenge.forEachIndexed { i, g ->
                // No uppercase() while typing (some keyboards repeat text when the value is rewritten); the check ignores case.
                KeyField(typed[i], { v -> typed = typed.toMutableList().also { it[i] = v } }, "Group ${g + 1}", tag = "group:$g")
            }
            MainButton("Continue", { if (!vm.confirmKit(typed)) vm.fail("The groups don't match. Check what you wrote down.") },
                Modifier.fillMaxWidth().testTag("kitDone"), enabled = typed.all { it.length == 4 } && !vm.busy)
        }
    }
}

// ── unlock & recovery ────────────────────────────────────────────────────────
@Composable
private fun UnlockScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by remember { mutableStateOf("") }
    var hintShown by remember { mutableStateOf(false) }
    val submit = { if (pw.isNotEmpty()) { vm.unlock(pw.toCharArray()); pw = "" } }
    Page("", snackbar, busy = vm.busy) {
        BrandHeader("Vault locked")
        PanelCard {
            PasswordField(pw, { pw = it }, "Master password", tag = "unlockPw", imeAction = ImeAction.Go, onDone = submit,
                isError = vm.unlockError != null)
            vm.unlockError?.let { ErrorText(it) }
            if (vm.waitSeconds > 0) WarnText("Too many attempts. Try again in ${vm.waitSeconds} s.")
            MainButton("Unlock", submit, Modifier.fillMaxWidth().testTag("unlock"), Icons.Filled.LockOpen,
                enabled = !vm.busy && vm.waitSeconds == 0 && pw.isNotEmpty())
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
    var key by remember { mutableStateOf("") }
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

@Composable
private fun NewPasswordScreen(vm: AppViewModel, snackbar: SnackbarHostState, forced: Boolean) {
    var current by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    Page(if (forced) "Set a new master password" else "Change master password", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            if (forced) Text("You unlocked with the recovery key. Choose a new master password; the recovery key stays valid.", color = Palette.fgPrimary)
            else PasswordField(current, { current = it }, "Current master password", tag = "currentPw")
            val ok = newPasswordFields(vm, pw, { pw = it }, confirm, { confirm = it }, hint, { hint = it })
            MainButton("Save", {
                vm.setNewPassword(if (forced) null else current.toCharArray(), pw.toCharArray(), hint)
                current = ""; pw = ""; confirm = ""
            }, Modifier.fillMaxWidth().testTag("newPwSave"), Icons.Filled.Save, enabled = ok && (forced || current.isNotEmpty()))
        }
    }
}

// ── list, detail, edit ───────────────────────────────────────────────────────
@Composable
private fun ListScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val payload by vm.payload.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(VaultEdits.Sort.AZ) }
    val entries = payload?.let { VaultEdits.visible(it, query, sort) }.orEmpty()
    val total = payload?.activeEntries?.size ?: 0
    Page(
        "Mobile Vault", snackbar, busy = vm.busy, scroll = false, titleColor = Palette.orange,
        actions = {
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings", tint = Palette.fgSecondary) }
            IconButton(onClick = { vm.lockNow() }, Modifier.testTag("lock")) { Icon(Icons.Filled.Lock, "Lock", tint = Palette.red) }
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
        PlainField(query, { query = it }, "Search service, username or URL", tag = "search", leading = Icons.Filled.Search)
        ChoiceRow("", VaultEdits.Sort.entries, sort, { if (it == VaultEdits.Sort.AZ) "A–Z" else "Recently modified" }) { sort = it }
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
                    }
                    if (e.favourite) Icon(Icons.Filled.Star, "Favourite", tint = Palette.orange)
                }
                if (e != entries.last()) HorizontalDivider(Modifier.padding(start = 64.dp), color = Palette.sep, thickness = 0.5.dp)
            }
        }
    }
}

/** The vault has a recovery key whose Emergency Kit was never confirmed (the app locked on that screen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitPendingCard(vm: AppViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    PanelCard(Modifier.testTag("kitPending"), title = "Recovery key not confirmed") {
        Muted("The app locked before you confirmed the Emergency Kit. If you didn't write down or save that key, create a new one, or remove it.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorButton("New recovery key", { confirm = true }, Palette.blue, icon = Icons.Filled.Key)
            OutlineButton("Remove recovery key", { remove = true }, Palette.red, Modifier.testTag("kitPendingRemove"), Icons.Filled.Delete)
        }
    }
    if (confirm) ConfirmDialog(
        "New recovery key?", "Any key or Emergency Kit you saved for this vault stops working.",
        confirm = "Continue", onConfirm = { confirm = false; vm.regenerateRecoveryKey() }, onDismiss = { confirm = false },
    )
    if (remove) RemoveRecoveryKeyDialogs(vm) { remove = false }
}

/** "Remove recovery key": master password first, then a confirmation, then the slot is dropped and saved. */
@Composable
private fun RemoveRecoveryKeyDialogs(vm: AppViewModel, onDone: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf(false) }
    if (!verified) ConfirmDialog(
        "Remove recovery key", "Enter your master password to continue.",
        confirm = "Continue", confirmTag = "removePwOk", confirmEnabled = pw.isNotEmpty() && !vm.busy,
        onConfirm = { vm.checkPasswordForRemoval(pw.toCharArray()) { verified = true }; pw = "" }, onDismiss = onDone,
    ) { PasswordField(pw, { pw = it }, "Master password", tag = "removePw") }
    else ConfirmDialog(
        "Remove the recovery key?",
        "Without a recovery key, a forgotten master password means all passwords in this vault are lost. Any printed Emergency Kit stops working.",
        confirm = "Remove", confirmColor = Palette.red, confirmTag = "removeConfirm",
        onConfirm = { onDone(); vm.removeRecoveryKey() }, onDismiss = onDone,
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
        Muted("Modified ${localTime(e.modified)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorButton("Edit", { vm.go(Screen.Edit(e.id)) }, Palette.blue, Modifier.weight(1f), Icons.Filled.Edit)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(fav, { fav = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.orange, checkedThumbColor = Palette.onOrange))
                Spacer(Modifier.width(10.dp)); Text("Favourite", color = Palette.fgPrimary)
            }
        }
        MainButton("Save", {
            val base = orig ?: Entry(service = service.trim(), password = password)
            vm.saveEntry(base.copy(service = service.trim(), username = username.trim(), password = password,
                url = url.trim(), notes = notes, favourite = fav))
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
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Palette.orange, checkmarkColor = Palette.onOrange))
        Text(label, color = Palette.fgPrimary)
    }
}

// ── settings, import, export, about ──────────────────────────────────────────
@Composable
private fun SettingsScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val s by vm.settings.collectAsState()
    var confirmRegen by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val pickImport = rememberImportPicker(vm)
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
                ColorButton("Create recovery key", { vm.regenerateRecoveryKey() }, Palette.green, Modifier.fillMaxWidth().testTag("addRecoveryKey"), Icons.Filled.Key)
            }
        }
        PanelCard(title = "Backup") {
            Muted("Coming soon: encrypted backups to your own server.")
        }
        PanelCard(title = "Import / Export") {
            ColorButton("Import from desktop (merge)", pickImport, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.FileDownload)
            ColorButton("Export for desktop", { vm.go(Screen.Export) }, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.FileUpload)
        }
        PanelCard(title = "About") {
            SecondaryButton("About Mobile Vault", { vm.go(Screen.About) }, Modifier.fillMaxWidth(), Icons.Filled.Info)
        }
    }
    if (confirmRegen) ConfirmDialog(
        "New recovery key?", "Your current recovery key and printed Emergency Kit stop working as soon as the new key is shown.",
        confirm = "Continue", onConfirm = { confirmRegen = false; vm.regenerateRecoveryKey() }, onDismiss = { confirmRegen = false },
    )
    if (remove) RemoveRecoveryKeyDialogs(vm) { remove = false }
}

@Composable
private fun ImportPasswordScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by remember { mutableStateOf("") }
    val submit = { vm.importDecrypt(pw.toCharArray()); pw = "" }
    Page("Import from desktop", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Muted("Enter the password of the desktop vault file.")
            PasswordField(pw, { pw = it }, "Desktop vault password", imeAction = ImeAction.Go, onDone = submit)
            MainButton("Open file", submit, Modifier.fillMaxWidth(), Icons.Filled.FolderOpen, enabled = pw.isNotEmpty() && !vm.busy)
        }
    }
}

@Composable
private fun ImportPreviewScreen(vm: AppViewModel, snackbar: SnackbarHostState, count: Int) {
    val unlocked = vm.payload.collectAsState().value != null
    Page("Import from desktop", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("The file contains $count entries.", color = Palette.fgPrimary, style = MaterialTheme.typography.titleMedium)
            if (unlocked) {
                Muted("Entries that already exist with the same service, username and password are skipped.")
                ColorButton("Merge into this vault", { vm.importMerge() }, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.FileDownload,
                    enabled = !vm.busy)
            } else {
                ColorButton("Create a new vault with them", { vm.importAsNewVault() }, Palette.violet, Modifier.fillMaxWidth(), Icons.Filled.Add,
                    enabled = !vm.busy)
            }
        }
    }
}

@Composable
private fun ExportScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.externalDone()
        if (uri != null) { vm.exportTo(uri, pw.toCharArray()); pw = ""; confirm = "" }
    }
    Page("Export for desktop", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        PanelCard {
            Text("Creates a file the desktop app (Shu PassVault) can open.", color = Palette.fgPrimary)
            WarnText("Notes, tags and favourites are not part of the desktop format and are left out.")
            Muted("Choose a password for the exported file. Anyone with the file and this password can read all entries.")
            PasswordField(pw, { pw = it }, "Export password")
            StrengthMeter(pw)
            PasswordField(confirm, { confirm = it }, "Repeat export password", isError = confirm.isNotEmpty() && confirm != pw)
            MainButton("Choose where to save", { vm.externalStarted(); save.launch("mobilevault-export.vault") },
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
