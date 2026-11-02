package com.spirtosan.mobilevault.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spirtosan.mobilevault.MobileVaultApp
import java.util.concurrent.TimeUnit

/**
 * Uploads the current vault file. Queued after every save, on app start and on unlock; only one
 * chain exists (unique work, APPEND_OR_REPLACE), and each run reads the newest file from disk.
 * No foreground service: HyperOS may delay background work, so opening the app retries too.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MobileVaultApp
        val backup = app.backup
        val target = backup.target() ?: return Result.success()
        val uploader = BackupUploader(
            target,
            readVault = { if (app.repo.exists()) app.repo.read() else null },
            status = { backup.status.value },
            update = { backup.updateStatus(it) },
        )
        return when (uploader.run()) {
            BackupUploader.Outcome.RETRY -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val NAME = "vault-upload"

        /** Queue an upload if this phone is paired. [wifiOnly]: the "Only on Wi-Fi" setting (unmetered networks). */
        fun enqueue(context: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
