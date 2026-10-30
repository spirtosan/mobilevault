package com.spirtosan.mobilevault.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.spirtosan.mobilevault.BuildConfig
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.data.Settings
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.security.PasswordGenerator
import kotlin.math.roundToInt

private const val MIN_MASTER_LENGTH = 8

@Composable
fun AppRoot(vm: AppViewModel, finish: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
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

// ── onboarding & create ──────────────────────────────────────────────────────
@Composable
private fun OnboardingScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val pickImport = rememberImportPicker(vm)
    Page("Mobile Vault", snackbar, busy = vm.busy) {
        Text("Your passwords, encrypted on this phone.", style = MaterialTheme.typography.titleMedium)
        Muted("Nothing leaves the phone unless you export it. Backups to your own server come in a later version.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.go(Screen.Create(null)) }, Modifier.fillMaxWidth().testTag("create")) { Text("Create new vault") }
        OutlinedButton(onClick = pickImport, Modifier.fillMaxWidth()) { Text("Import from desktop (.vault)") }
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Restore from server (coming soon)") }
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
    if (pw.isNotEmpty() && pw.length < MIN_MASTER_LENGTH) Muted("Use at least $MIN_MASTER_LENGTH characters.")
    PasswordField(confirm, onConfirm, "Repeat master password", tag = "pw2", isError = confirm.isNotEmpty() && confirm != pw)
    PlainField(hint, onHint, "Hint (optional)", tag = "hint")
    val chars = pw.toCharArray()
    val hintProblem = if (hint.isBlank()) null else vm.hintProblem(hint, chars)
    chars.fill('\u0000')
    if (hintProblem != null) Text(hintProblem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Muted("The hint is stored unencrypted and shown on the unlock screen to anyone holding the phone. Never put the password itself in it.")
    CalibrationInfo(vm)
    return pw.length >= MIN_MASTER_LENGTH && pw == confirm && hintProblem == null && vm.calibration != null && !vm.busy
}

@Composable
private fun CreateScreen(vm: AppViewModel, snackbar: SnackbarHostState, s: Screen.Create) {
    var pw by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf("") }
    Page("Create vault", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        s.imported?.let { Muted("${it.activeEntries.size} entries from the desktop file will be in the new vault.") }
        val ok = newPasswordFields(vm, pw, { pw = it }, confirm, { confirm = it }, hint, { hint = it })
        Button(
            onClick = { vm.createVault(pw.toCharArray(), hint, s.imported); pw = ""; confirm = "" },
            enabled = ok, modifier = Modifier.fillMaxWidth().testTag("createSubmit"),
        ) { Text("Create") }
    }
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
        Text("This is your recovery key. It opens the vault if you forget the master password.", style = MaterialTheme.typography.titleSmall)
        Card(colors = CardDefaults.cardColors(containerColor = Palette.bgPanel)) {
            Text(
                k.groups.chunked(3).joinToString("\n") { it.joinToString("-") },
                fontFamily = FontFamily.Monospace, fontSize = 22.sp, lineHeight = 32.sp,
                modifier = Modifier.padding(16.dp).testTag("recoveryKey"),
            )
        }
        Muted("Write it on paper or save the PDF and print it. Keep it offline. It is shown only now.")
        OutlinedButton(onClick = { vm.externalStarted(); savePdf.launch("MobileVault-EmergencyKit.pdf") }, Modifier.fillMaxWidth()) {
            Text("Save Emergency Kit as PDF")
        }
        HorizontalDivider()
        Text("To make sure you have it, type these groups:", style = MaterialTheme.typography.titleSmall)
        k.challenge.forEachIndexed { i, g ->
            PlainField(typed[i], { v -> typed = typed.toMutableList().also { it[i] = v.uppercase() } },
                "Group ${g + 1}", tag = "group:$g", keyboardType = KeyboardType.Password)
        }
        Button(
            onClick = { if (!vm.confirmKit(typed)) vm.message = "The groups don't match. Check what you wrote down." },
            enabled = typed.all { it.length == 4 } && !vm.busy, modifier = Modifier.fillMaxWidth().testTag("kitDone"),
        ) { Text("Continue") }
    }
}

// ── unlock & recovery ────────────────────────────────────────────────────────
@Composable
private fun UnlockScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by remember { mutableStateOf("") }
    var hintShown by remember { mutableStateOf(false) }
    val submit = { if (pw.isNotEmpty()) { vm.unlock(pw.toCharArray()); pw = "" } }
    Page("Unlock", snackbar, busy = vm.busy) {
        PasswordField(pw, { pw = it }, "Master password", tag = "unlockPw", imeAction = ImeAction.Go, onDone = submit,
            isError = vm.unlockError != null)
        vm.unlockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (vm.waitSeconds > 0) Text("Too many attempts. Try again in ${vm.waitSeconds} s.", color = Palette.orange)
        Button(onClick = submit, enabled = !vm.busy && vm.waitSeconds == 0 && pw.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("unlock")) { Text("Unlock") }
        if (vm.showHintButton && vm.hint != null) {
            if (hintShown) Text("Hint: ${vm.hint}", color = Palette.orange)
            else TextButton(onClick = { hintShown = true }) { Text("Show hint") }
        }
        TextButton(onClick = { vm.go(Screen.Recovery) }) { Text("Forgot password? Use recovery key") }
    }
}

@Composable
private fun RecoveryScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var key by remember { mutableStateOf("") }
    Page("Recovery key", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        Muted("Type the key from your Emergency Kit. Dashes and case don't matter.")
        PlainField(key, { key = it.uppercase() }, "Recovery key", singleLine = false, keyboardType = KeyboardType.Password)
        Button(onClick = { vm.unlockWithRecoveryKey(key) }, enabled = key.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Unlock")
        }
        Muted("After unlocking you will set a new master password.")
    }
}

@Composable
private fun NewPasswordScreen(vm: AppViewModel, snackbar: SnackbarHostState, forced: Boolean) {
    var current by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    Page(if (forced) "Set a new master password" else "Change master password", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        if (forced) Muted("You unlocked with the recovery key. Choose a new master password; the recovery key stays valid.")
        else PasswordField(current, { current = it }, "Current master password", tag = "currentPw")
        val ok = newPasswordFields(vm, pw, { pw = it }, confirm, { confirm = it }, hint, { hint = it })
        Button(
            onClick = {
                vm.setNewPassword(if (forced) null else current.toCharArray(), pw.toCharArray(), hint)
                current = ""; pw = ""; confirm = ""
            },
            enabled = ok && (forced || current.isNotEmpty()), modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

// ── list, detail, edit ───────────────────────────────────────────────────────
@Composable
private fun ListScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val payload by vm.payload.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(VaultEdits.Sort.AZ) }
    val entries = payload?.let { VaultEdits.visible(it, query, sort) }.orEmpty()
    Page(
        "Mobile Vault", snackbar, busy = vm.busy, scroll = false,
        actions = {
            IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
            IconButton(onClick = { vm.lockNow() }, Modifier.testTag("lock")) { Icon(Icons.Default.Lock, "Lock") }
        },
        floating = {
            FloatingActionButton(onClick = { vm.go(Screen.Edit(null)) }, Modifier.testTag("add")) { Icon(Icons.Default.Add, "Add") }
        },
    ) {
        PlainField(query, { query = it }, "Search service, username or URL", tag = "search")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted("Sort:")
            ChoiceRow("", VaultEdits.Sort.entries, sort, { if (it == VaultEdits.Sort.AZ) "A–Z" else "Recently modified" }) { sort = it }
        }
        if (entries.isEmpty()) Muted(if (query.isEmpty()) "No entries yet. Tap + to add one." else "Nothing found.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.id }) { e ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Palette.bgPanel),
                    modifier = Modifier.fillMaxWidth().clickable { vm.go(Screen.Detail(e.id)) }.testTag("entry:${e.service}"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.service, style = MaterialTheme.typography.titleMedium)
                            if (e.username.isNotEmpty()) Muted(e.username)
                        }
                        if (e.favourite) Icon(Icons.Default.Star, "Favourite", tint = Palette.orange)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(vm: AppViewModel, snackbar: SnackbarHostState, id: String) {
    val payload by vm.payload.collectAsState()
    val e = payload?.entries?.firstOrNull { it.id == id && !it.deleted } ?: return
    var show by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Page(e.service, snackbar, onBack = { vm.back() }, busy = vm.busy, actions = {
        IconButton(onClick = { vm.toggleFavourite(e) }) {
            Icon(Icons.Default.Star, if (e.favourite) "Unfavourite" else "Favourite", tint = if (e.favourite) Palette.orange else Palette.fgMuted)
        }
    }) {
        DetailRow("Username", e.username, onCopy = { vm.copy("Username", e.username) })
        DetailRow("Password", if (show) e.password else "••••••••••", mono = true, onCopy = { vm.copy("Password", e.password) }) {
            TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
        }
        if (e.url.isNotEmpty()) DetailRow("URL", e.url, onCopy = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, e.url.toUri())) }
        })
        if (e.notes.isNotEmpty()) { Muted("Notes"); Text(e.notes) }
        Muted("Modified ${e.modified}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.go(Screen.Edit(e.id)) }, Modifier.weight(1f)) { Text("Edit") }
            OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f)) { Text("Delete", color = Palette.red) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${e.service}?") },
        text = { Text("The entry is removed from the list. Older copies stay in local snapshots.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteEntry(e.id) }) { Text("Delete", color = Palette.red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false, onCopy: () -> Unit, extra: @Composable () -> Unit = {}) {
    if (value.isEmpty()) return
    Column {
        Muted(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, Modifier.weight(1f), fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
            extra()
            TextButton(onClick = onCopy, Modifier.testTag("copy:$label")) { Text(if (label == "URL") "Open" else "Copy") }
        }
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
        PlainField(service, { service = it }, "Service *", tag = "service")
        PlainField(username, { username = it }, "Username", tag = "username", keyboardType = KeyboardType.Email)
        PasswordField(password, { password = it }, "Password *", tag = "password", mono = true)
        StrengthMeter(password)
        TextButton(onClick = { showGen = !showGen }) { Text(if (showGen) "Hide generator" else "Generate password") }
        if (showGen) GeneratorPanel(vm.generator) { password = it }
        PlainField(url, { url = it }, "URL", keyboardType = KeyboardType.Uri)
        PlainField(notes, { notes = it }, "Notes", singleLine = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(fav, { fav = it }); Spacer(Modifier.padding(4.dp)); Text("Favourite")
        }
        Button(
            onClick = {
                val base = orig ?: Entry(service = service.trim(), password = password)
                vm.saveEntry(base.copy(service = service.trim(), username = username.trim(), password = password,
                    url = url.trim(), notes = notes, favourite = fav))
            },
            enabled = service.isNotBlank() && password.isNotEmpty() && !vm.busy,
            modifier = Modifier.fillMaxWidth().testTag("save"),
        ) { Text("Save") }
    }
}

@Composable
private fun GeneratorPanel(gen: PasswordGenerator, onGenerated: (String) -> Unit) {
    var o by remember { mutableStateOf(PasswordGenerator.Options()) }
    Card(colors = CardDefaults.cardColors(containerColor = Palette.bgPanel)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(o.passphrase, { o = o.copy(passphrase = it) }); Spacer(Modifier.padding(4.dp)); Text("Passphrase (words)")
            }
            if (o.passphrase) {
                Text("Words: ${o.words}")
                Slider(o.words.toFloat(), { o = o.copy(words = it.roundToInt()) },
                    valueRange = PasswordGenerator.MIN_WORDS.toFloat()..PasswordGenerator.MAX_WORDS.toFloat(),
                    steps = PasswordGenerator.MAX_WORDS - PasswordGenerator.MIN_WORDS - 1)
            } else {
                Text("Length: ${o.length}")
                Slider(o.length.toFloat(), { o = o.copy(length = it.roundToInt()) },
                    valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat())
                CheckRow("a–z", o.lower) { o = o.copy(lower = it) }
                CheckRow("A–Z", o.upper) { o = o.copy(upper = it) }
                CheckRow("0–9", o.digits) { o = o.copy(digits = it) }
                CheckRow("Symbols", o.symbols) { o = o.copy(symbols = it) }
            }
            Button(onClick = { onGenerated(gen.generate(o)) }, Modifier.fillMaxWidth()) { Text("Generate") }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onChange); Text(label) }
}

// ── settings, import, export, about ──────────────────────────────────────────
@Composable
private fun SettingsScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val s by vm.settings.collectAsState()
    var confirmRegen by remember { mutableStateOf(false) }
    val pickImport = rememberImportPicker(vm)
    Page("Settings", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        ChoiceRow("Auto-lock after", Settings.AUTO_LOCK_CHOICES, s.autoLockMinutes, { "$it min" }) {
            vm.updateSettings(s.copy(autoLockMinutes = it))
        }
        ChoiceRow("Lock when leaving the app", Settings.GRACE_CHOICES, s.backgroundGraceSeconds,
            { if (it == 0) "Immediately" else if (it == 30) "After 30 s" else "After 1 min" }) {
            vm.updateSettings(s.copy(backgroundGraceSeconds = it))
        }
        ChoiceRow("Clear copied passwords after", Settings.CLIPBOARD_CHOICES, s.clipboardClearSeconds,
            { if (it == 0) "Never" else "$it s" }) { vm.updateSettings(s.copy(clipboardClearSeconds = it)) }
        HorizontalDivider()
        OutlinedButton(onClick = { vm.go(Screen.NewPassword(forced = false)) }, Modifier.fillMaxWidth()) { Text("Change master password") }
        OutlinedButton(onClick = { confirmRegen = true }, Modifier.fillMaxWidth()) { Text("Regenerate recovery key") }
        OutlinedButton(onClick = pickImport, Modifier.fillMaxWidth()) { Text("Import from desktop (merge)") }
        OutlinedButton(onClick = { vm.go(Screen.Export) }, Modifier.fillMaxWidth()) { Text("Export for desktop") }
        OutlinedButton(onClick = { vm.go(Screen.About) }, Modifier.fillMaxWidth()) { Text("About") }
    }
    if (confirmRegen) AlertDialog(
        onDismissRequest = { confirmRegen = false },
        title = { Text("New recovery key?") },
        text = { Text("Your current recovery key and printed Emergency Kit will stop working once you confirm the new one.") },
        confirmButton = { TextButton(onClick = { confirmRegen = false; vm.regenerateRecoveryKey() }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ImportPasswordScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var pw by remember { mutableStateOf("") }
    val submit = { vm.importDecrypt(pw.toCharArray()); pw = "" }
    Page("Import from desktop", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        Muted("Enter the password of the desktop vault file.")
        PasswordField(pw, { pw = it }, "Desktop vault password", imeAction = ImeAction.Go, onDone = submit)
        Button(onClick = submit, enabled = pw.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Open file") }
    }
}

@Composable
private fun ImportPreviewScreen(vm: AppViewModel, snackbar: SnackbarHostState, count: Int) {
    val unlocked = vm.payload.collectAsState().value != null
    Page("Import from desktop", snackbar, onBack = { vm.back() }, busy = vm.busy) {
        Text("The file contains $count entries.", style = MaterialTheme.typography.titleMedium)
        if (unlocked) {
            Muted("Entries that already exist with the same service, username and password are skipped.")
            Button(onClick = { vm.importMerge() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Merge into this vault") }
        } else {
            Button(onClick = { vm.importAsNewVault() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Create a new vault with them") }
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
        Text("Creates a file the desktop app (Shu PassVault) can open.", style = MaterialTheme.typography.titleSmall)
        Text("Notes, tags and favourites are not part of the desktop format and are left out.", color = Palette.orange)
        Muted("Choose a password for the exported file. Anyone with the file and this password can read all entries.")
        PasswordField(pw, { pw = it }, "Export password")
        StrengthMeter(pw)
        PasswordField(confirm, { confirm = it }, "Repeat export password", isError = confirm.isNotEmpty() && confirm != pw)
        Button(
            onClick = { vm.externalStarted(); save.launch("mobilevault-export.vault") },
            enabled = pw.length >= MIN_MASTER_LENGTH && pw == confirm && !vm.busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Choose where to save") }
    }
}

@Composable
private fun AboutScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    Page("About", snackbar, onBack = { vm.back() }) {
        Text("Mobile Vault ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Muted("Licensed under the GNU General Public License v3.0 or later.")
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/spirtosan/mobilevault".toUri())) }
        }) { Text("github.com/spirtosan/mobilevault") }
        Muted("Passphrases use the EFF Short Wordlist 1 by the Electronic Frontier Foundation (CC BY 3.0 US).")
        Muted("Encryption: AES-256-GCM, Argon2id (own Kotlin implementation, RFC 9106), recovery key via HKDF-SHA256.")
    }
}
