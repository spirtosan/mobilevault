package com.spirtosan.mobilevault.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.spirtosan.mobilevault.BuildConfig
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.spirtosan.mobilevault.security.BiometricPolicy
import com.spirtosan.mobilevault.security.Reminders
import javax.crypto.Cipher

/** Null if a strong biometric (fingerprint) can be used; otherwise why not, in plain words. */
fun biometricUnavailableReason(context: Context): String? = biometricReasonFor(strongBiometricStatus(context))

/** `canAuthenticate(BIOMETRIC_STRONG)`; logged in debug builds (Task 13: why is the switch off on this phone?). */
fun strongBiometricStatus(context: Context): Int = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG).also {
    if (BuildConfig.DEBUG) Log.d("MobileVault", "canAuthenticate(BIOMETRIC_STRONG) = $it (${biometricStatusName(it)})")
}

fun biometricStatusName(code: Int) = when (code) {
    BiometricManager.BIOMETRIC_SUCCESS -> "SUCCESS"
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NONE_ENROLLED"
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO_HARDWARE"
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "HW_UNAVAILABLE"
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "SECURITY_UPDATE_REQUIRED"
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "UNSUPPORTED"
    BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "UNKNOWN"
    else -> "code $code"
}

/**
 * Plain-word reason for a `canAuthenticate(BIOMETRIC_STRONG)` result, null on success. Only strong (Class 3)
 * biometrics are used: a weak sensor can't unlock the Keystore key that wraps the vault key (security decision).
 */
fun biometricReasonFor(code: Int): String? = when (code) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> NONE_ENROLLED_TEXT
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
        "This phone has no fingerprint sensor that is secure enough (Class 3 / strong). Use your master password."
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "The fingerprint sensor is not available right now. Try again later."
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "The phone needs a security update before fingerprint unlock can be used."
    else -> "Fingerprint unlock is not available on this phone."
}

const val NONE_ENROLLED_TEXT = "Set up a fingerprint in the phone's Settings → Fingerprint first, then come back here."

/** The system screen to add a fingerprint: the enrol screen on Android 11+, else the security settings. */
fun fingerprintSettingsIntent(): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG)
    else Intent(Settings.ACTION_SECURITY_SETTINGS)

/**
 * BiometricPrompt with a Keystore cipher (CryptoObject): the cipher only works after a real
 * fingerprint match. [onFailed]: a finger that didn't match (the prompt stays open).
 */
fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    cipher: Cipher,
    onSuccess: (Cipher) -> Unit,
    onFailed: () -> Unit = {},
    onError: (code: Int, text: CharSequence) -> Unit = { _, _ -> },
) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            result.cryptoObject?.cipher?.let(onSuccess)
        }
        override fun onAuthenticationFailed() = onFailed()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Mobile Vault")
            .setNegativeButtonText("Use master password")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build(),
        BiometricPrompt.CryptoObject(cipher),
    )
}

/** Unlock screen: open the prompt (automatically once per visit, or from the button). */
fun offerBiometricUnlock(vm: AppViewModel, activity: FragmentActivity) {
    val cipher = vm.biometricUnlockCipher() ?: return
    showBiometricPrompt(activity, "Unlock vault", cipher,
        onSuccess = { vm.unlockWithBiometric(it) },
        onFailed = { vm.onBiometricFailed() },
        onError = { code, _ ->
            if (code == BiometricPrompt.ERROR_LOCKOUT || code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) vm.onBiometricLockout()
        })
}

/** Settings → Security: the fingerprint switch, the "ask for the password every N days" choice. */
@Composable
internal fun BiometricSettings(vm: AppViewModel) {
    val context = LocalContext.current
    // Checked again every time the screen comes back (Task 13): e.g. after adding a fingerprint in the phone's settings.
    var status by remember { mutableStateOf(strongBiometricStatus(context)) }
    // Like the file pickers: a short trip to the phone's settings doesn't trigger the background lock (idle and screen-off locks still apply).
    var inPhoneSettings by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        status = strongBiometricStatus(context)
        if (inPhoneSettings) { inPhoneSettings = false; vm.externalDone() }
        onPauseOrDispose { }
    }
    val unavailable = biometricReasonFor(status)
    val days by vm.biometricDays.collectAsState(BiometricPolicy.DEFAULT_DAYS)
    var askPassword by remember { mutableStateOf(false) }
    var pw by rememberSecret()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(vm.biometricEnabled, { on -> if (on) askPassword = true else vm.disableBiometric() },
            Modifier.testTag("bioSwitch"), enabled = unavailable == null || vm.biometricEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.orange, checkedThumbColor = Palette.onOrange,
                uncheckedThumbColor = Palette.fgSecondary, uncheckedTrackColor = Palette.bgInput, uncheckedBorderColor = Palette.fgSecondary))
        Spacer(Modifier.width(10.dp)); Text("Unlock with fingerprint", color = Palette.fgPrimary)
    }
    if (unavailable != null && !vm.biometricEnabled) {
        // normal text colour, right under the switch (Task 13: the faint line was easy to miss)
        Text(unavailable, color = Palette.fgPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("bioReason"))
        if (status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) ColorButton("Open phone settings", {
            vm.externalStarted(); inPhoneSettings = true
            val opened = runCatching { context.startActivity(fingerprintSettingsIntent()) }.isSuccess ||
                runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }.isSuccess
            if (!opened) {
                inPhoneSettings = false; vm.externalDone()
                vm.fail("Couldn't open the phone's settings. Open Settings → Fingerprint yourself.")
            }
        }, Palette.steel, Modifier.fillMaxWidth().testTag("bioOpenSettings"), Icons.Filled.Settings)
    } else if (unavailable != null) Muted(unavailable)
    else if (!vm.biometricEnabled)
        // Task 13: an "off" switch looks grey on the dark theme; say that it can be turned on.
        Text("Off. Tap the switch to turn it on: you enter your master password once, then touch the sensor.",
            color = Palette.fgPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("bioOffHint"))
    else if (vm.biometricEnabled) {
        ChoiceRow("Ask for the master password every", BiometricPolicy.DAY_CHOICES, days, { "$it days" }) { vm.setBiometricDays(it) }
        Muted("The master password is also needed after a restart and after ${BiometricPolicy.MAX_FAILURES} failed fingerprint attempts.")
    }
    if (askPassword) ConfirmDialog(
        "Unlock with fingerprint", "Enter your master password, then touch the sensor.",
        confirm = "Continue", confirmTag = "bioPwOk", confirmEnabled = pw.isNotEmpty() && !vm.busy,
        onConfirm = {
            askPassword = false
            val activity = context as FragmentActivity
            vm.checkPasswordForBiometric(pw.toCharArray()) { cipher ->
                showBiometricPrompt(activity, "Turn on fingerprint unlock", cipher, onSuccess = { vm.finishEnableBiometric(it) })
            }
            pw = ""
        },
        onDismiss = { askPassword = false; pw = "" },
    ) { PasswordField(pw, { pw = it }, "Master password", tag = "bioPw") }
}

/** The fingerprint button under the password field (when the policy allows it). */
@Composable
internal fun BiometricUnlockButton(vm: AppViewModel) {
    val activity = LocalContext.current as FragmentActivity
    ColorButton("Unlock with fingerprint", { offerBiometricUnlock(vm, activity) }, Palette.steel,
        Modifier.fillMaxWidth().testTag("bioUnlock"), Icons.Filled.Fingerprint, enabled = !vm.busy)
}

// ── password reminders ───────────────────────────────────────────────────────

/** Every 30 days without typing the master password: type it once, nothing changes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PracticePasswordCard(vm: AppViewModel) {
    if (!Reminders.practiceDue(vm.reminderTimes, vm.reminderNow()) && !vm.practiceWrong) return
    var pw by rememberSecret()
    var hintShown by remember { mutableStateOf(false) }
    PanelCard(Modifier.testTag("practiceCard"), title = "Practice your master password") {
        Muted("You rarely type it. Type it once now so you don't forget it. Nothing is changed.")
        PasswordField(pw, { pw = it }, "Master password", tag = "practicePw", imeAction = ImeAction.Done,
            onDone = { if (pw.isNotEmpty()) { vm.practicePassword(pw.toCharArray()); pw = "" } }, isError = vm.practiceWrong)
        if (vm.practiceWrong) {
            ErrorText("That is not your master password.", "practiceWrong")
            if (hintShown) vm.practiceHint?.let { WarnText("Hint: $it") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.practiceHint != null && !hintShown) OutlineButton("Show hint", { hintShown = true }, Palette.amber)
                ColorButton("Set a new password now", { vm.newPasswordFromPractice() }, Palette.blue,
                    Modifier.testTag("practiceNewPw"), Icons.Filled.Password)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MainButton("Check", { vm.practicePassword(pw.toCharArray()); pw = "" }, Modifier.testTag("practiceCheck"),
                enabled = pw.isNotEmpty() && !vm.busy)
            OutlineButton("Not now", { vm.dismissPractice() }, Palette.fgSecondary, Modifier.testTag("practiceDismiss"))
        }
    }
}

/** Once a year, only with a recovery key: is the printed Emergency Kit still where it should be? */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KitCheckCard(vm: AppViewModel) {
    if (!Reminders.kitDue(vm.reminderTimes, vm.hasRecoveryKey, vm.reminderNow()) || vm.kitPending) return
    var makeNew by remember { mutableStateOf(false) }
    PanelCard(Modifier.testTag("kitCheckCard"), title = "Is your Emergency Kit still safe?") {
        Muted("Check that the printed recovery key is still where you keep it, and that nobody else has seen it.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorButton("Yes", { vm.kitStillSafe() }, Palette.green, Modifier.testTag("kitSafe"))
            ColorButton("Make a new key", { makeNew = true }, Palette.blue, Modifier.testTag("kitNew"), Icons.Filled.Key)
        }
    }
    if (makeNew) NewRecoveryKeyDialog(vm) { makeNew = false }
}

/** Debug builds only: fast-forward the reminder clock (practice card, kit card, fingerprint N days, test restore). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DebugClockCard(vm: AppViewModel) {
    PanelCard(Modifier.testTag("debugClock"), title = "Debug: reminder clock") {
        val days = vm.reminderTimes.debugOffsetMs / (24 * 3_600_000L)
        Muted("Offset: +$days days (debug builds only)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 7, 30, 365).forEach { d -> SecondaryButton("+$d d", { vm.debugClockForward(d) }, Modifier.testTag("clock+$d")) }
            SecondaryButton("Reset", { vm.debugClockForward(0) }, Modifier.testTag("clockReset"))
        }
    }
}
