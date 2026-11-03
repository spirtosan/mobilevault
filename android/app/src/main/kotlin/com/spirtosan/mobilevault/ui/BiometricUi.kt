package com.spirtosan.mobilevault.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.spirtosan.mobilevault.security.BiometricPolicy
import javax.crypto.Cipher

/** Null if a strong biometric (fingerprint) can be used; otherwise why not, in plain words. */
fun biometricUnavailableReason(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No fingerprint is enrolled. Add one in the phone's settings first."
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "This phone has no fingerprint sensor of the strong class."
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "The fingerprint sensor is not available right now."
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "The phone needs a security update before fingerprint unlock can be used."
    else -> "Fingerprint unlock is not available on this phone."
}

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
    val unavailable = remember { biometricUnavailableReason(context) }
    val days by vm.biometricDays.collectAsState(BiometricPolicy.DEFAULT_DAYS)
    var askPassword by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(vm.biometricEnabled, { on -> if (on) askPassword = true else vm.disableBiometric() },
            Modifier.testTag("bioSwitch"), enabled = unavailable == null || vm.biometricEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.orange, checkedThumbColor = Palette.onOrange))
        Spacer(Modifier.width(10.dp)); Text("Unlock with fingerprint", color = Palette.fgPrimary)
    }
    if (unavailable != null) Muted(unavailable)
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
