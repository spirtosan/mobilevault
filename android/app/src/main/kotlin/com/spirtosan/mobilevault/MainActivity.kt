package com.spirtosan.mobilevault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.spirtosan.mobilevault.ui.AppRoot
import com.spirtosan.mobilevault.ui.AppViewModel
import com.spirtosan.mobilevault.ui.MobileVaultTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank thumbnail in the recent-apps list.
        // Debug builds only: `am start ... --ez debug_allow_screenshots true` leaves it off, for UI screenshots
        // with test data. BuildConfig.DEBUG is a constant false in release, so R8 removes the branch.
        val allowScreenshots = BuildConfig.DEBUG && intent.getBooleanExtra("debug_allow_screenshots", false)
        if (!allowScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { MobileVaultTheme { AppRoot(vm, ::finish) } }
    }

    // Any touch or key press counts as activity for the auto-lock timer.
    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.touch()
    }
}
