package com.spirtosan.mobilevault.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Colours of the desktop app (reference/desktop-passvault/password_manager.py, palette C). */
object Palette {
    val bgMain = Color(0xFF1A1A1A)
    val bgSidebar = Color(0xFF212121)
    val bgToolbar = Color(0xFF252525)
    val bgPanel = Color(0xFF2D2D2D)
    val bgInput = Color(0xFF333333)
    val fgPrimary = Color(0xFFE8E8E8)
    val fgSecondary = Color(0xFFA0A0A0)
    val fgMuted = Color(0xFF606060)
    val orange = Color(0xFFF39C12)
    val blue = Color(0xFF2980B9)
    val red = Color(0xFFE74C3C)
    val selectBg = Color(0xFF1F6AA5)
    val sep = Color(0xFF404040)
    /** Strength meter: Very weak … Very strong (desktop _S_COLORS). */
    val strength = listOf(Color(0xFFE74C3C), Color(0xFFE67E22), Color(0xFFF1C40F), Color(0xFF2ECC71), Color(0xFF27AE60))
}

private val scheme = darkColorScheme(
    primary = Palette.selectBg, onPrimary = Color.White,
    secondary = Palette.orange, onSecondary = Color.Black,
    tertiary = Palette.blue,
    background = Palette.bgMain, onBackground = Palette.fgPrimary,
    surface = Palette.bgMain, onSurface = Palette.fgPrimary,
    surfaceVariant = Palette.bgPanel, onSurfaceVariant = Palette.fgSecondary,
    surfaceContainer = Palette.bgSidebar, surfaceContainerHigh = Palette.bgToolbar,
    surfaceContainerHighest = Palette.bgInput,
    outline = Palette.sep, outlineVariant = Palette.sep,
    error = Palette.red,
)

/**
 * Dark theme, and IME_FLAG_NO_PERSONALIZED_LEARNING on every text field in the app, so keyboards
 * don't learn or remember what is typed (passwords, usernames, notes).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MobileVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        InterceptPlatformTextInput(
            interceptor = { request, next ->
                next.startInputMethod(object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val ic = request.createInputConnection(outAttributes)
                        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        return ic
                    }
                })
            },
            content = content,
        )
    }
}
