package com.spirtosan.mobilevault.bench

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Pvault1
import com.spirtosan.mobilevault.crypto.Pvault2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug/bench-only screen: times Argon2id at the four candidate settings and the desktop
 * PVAULT1 import (PBKDF2 600k). Start with `--ez autorun true` to run without tapping.
 * Results also go to logcat with tag [TAG] (timings only, no secrets).
 */
class BenchmarkActivity : ComponentActivity() {
    private var output by mutableStateOf("")
    private var running by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF1A1A1A))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Button(onClick = ::run, enabled = !running) { Text(if (running) "Running…" else "Run benchmark") }
                        Text(output, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
        if (savedInstanceState == null && intent.getBooleanExtra("autorun", false)) run()
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        output += line + "\n"
    }

    private fun run() {
        if (running) return
        running = true
        output = ""
        lifecycleScope.launch {
            val rt = Runtime.getRuntime()
            emit("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull()} cores=${rt.availableProcessors()} heapMax=${rt.maxMemory() / MiB}MiB")
            for (p in CONFIGS) {
                val r = withContext(Dispatchers.Default) { timeArgon2(p) }
                emit(r)
            }
            emit(withContext(Dispatchers.Default) { timePvault1() })
            emit("DONE")
            running = false
        }
    }

    private fun timeArgon2(p: Argon2Params): String {
        val label = "Argon2 ${p.mKiB / 1024}MiB/t${p.t}"
        val creates = mutableListOf<Long>()
        val unlocks = mutableListOf<Long>()
        val perUnlock = mutableListOf<Long>()
        return try {
            repeat(RUNS) {
                val pw = PASSWORD.toCharArray()
                val t0 = SystemClock.elapsedRealtime()
                val created = Pvault2.create(pw, "bench", kdf = p)
                val t1 = SystemClock.elapsedRealtime()
                val opened = Pvault2.unlockWithPassword(created.bytes, pw)
                val t2 = SystemClock.elapsedRealtime()
                opened.wipe(); created.vault.wipe(); created.recoveryKey.wipe()
                creates += t1 - t0; unlocks += t2 - t1; perUnlock += (t2 - t0) / 2
            }
            "$label  median(create+unlock)/2=${median(perUnlock)} ms  " +
                "create=${creates.joinToString("/")}  unlock=${unlocks.joinToString("/")}  OOM=no"
        } catch (e: OutOfMemoryError) {
            "$label  OOM=YES after ${creates.size} runs"
        }
    }

    private fun timePvault1(): String {
        val raw = assets.open("sample-pvault1.bin").use { it.readBytes() }
        val times = mutableListOf<Long>()
        var count = 0
        repeat(RUNS) {
            val t0 = SystemClock.elapsedRealtime()
            count = Pvault1.decrypt(SAMPLE_PASSWORD.toCharArray(), raw).entries.size
            times += SystemClock.elapsedRealtime() - t0
        }
        return "Pvault1.decrypt (PBKDF2 600k)  median=${median(times)} ms  runs=${times.joinToString("/")}  entries=$count"
    }

    private fun median(xs: List<Long>) = xs.sorted()[xs.size / 2]

    companion object {
        const val TAG = "MVBench"
        private const val RUNS = 3
        private const val MiB = 1024 * 1024
        private const val PASSWORD = "bench-password-not-secret"
        private const val SAMPLE_PASSWORD = "correct horse battery staple"   // public test vector
        private val CONFIGS = listOf(
            Argon2Params(mKiB = 65536, t = 3),
            Argon2Params(mKiB = 65536, t = 2),
            Argon2Params(mKiB = 32768, t = 3),
            Argon2Params(mKiB = 32768, t = 2),
        )
    }
}
