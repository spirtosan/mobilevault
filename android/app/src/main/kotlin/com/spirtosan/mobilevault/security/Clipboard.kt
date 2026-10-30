package com.spirtosan.mobilevault.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copies secrets marked as sensitive (hidden from the Android 13+ clipboard preview and from keyboards'
 * clipboard history), and clears them after the timeout and on lock.
 *
 * Android 10+ forbids *reading* the clipboard from the background, so we cannot check whether our text
 * is still there; we clear it unconditionally if we copied something and have not cleared it yet.
 */
class Clipboard(context: Context, private val scope: CoroutineScope) {
    private val cm = context.getSystemService(ClipboardManager::class.java)
    private var pending: Job? = null
    private var dirty = false

    fun copySecret(label: String, text: String, clearAfterSeconds: Int) {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        dirty = true
        pending?.cancel()
        if (clearAfterSeconds > 0) pending = scope.launch {
            delay(clearAfterSeconds * 1000L)
            clear()
        }
    }

    fun clear() {
        pending?.cancel(); pending = null
        if (!dirty) return
        runCatching { cm.clearPrimaryClip() }
        dirty = false
    }
}
