package com.spirtosan.mobilevault

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.spirtosan.mobilevault.ui.AppRoot
import com.spirtosan.mobilevault.ui.AppViewModel
import com.spirtosan.mobilevault.ui.MobileVaultTheme

@OptIn(ExperimentalComposeUiApi::class)
/** A FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank thumbnail in the recent-apps list.
        // Debug builds only: `am start ... --ez debug_allow_screenshots true` leaves it off, for UI screenshots
        // with test data. BuildConfig.DEBUG is a constant false in release, so R8 removes the branch.
        val allowScreenshots = BuildConfig.DEBUG && intent.getBooleanExtra("debug_allow_screenshots", false)
        if (!allowScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // No autofill (Task 11): other password managers / Google Autofill must neither fill nor read our fields.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        setContent {
            // Debug builds: test tags visible to `uiautomator dump` as resource ids, for adb-driven checks.
            val root = if (BuildConfig.DEBUG) Modifier.semantics { testTagsAsResourceId = true } else Modifier
            MobileVaultTheme { Box(root) { AppRoot(vm, ::finish) } }
        }
    }

    // Any touch or key press counts as activity for the auto-lock timer.
    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.touch()
    }
}
