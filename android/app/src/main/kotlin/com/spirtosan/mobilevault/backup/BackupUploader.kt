package com.spirtosan.mobilevault.backup

import com.spirtosan.mobilevault.crypto.Pvault2

/**
 * One upload attempt of the current vault file (already encrypted, so the vault need not be open):
 * `PUT /vaults/{id}/revs/{rev}`, then verify that the server lists the same rev with our sha256.
 *
 * - 201 → verified → success.
 * - 409 → same sha256 on the server: success. Different: the server has another file for this rev
 *   (restore of an older version, another device) → [BackupStatus.needsNewRev]; the next unlock
 *   re-seals with `max(local, remote) + 1`. Nothing is ever overwritten.
 * - 401/403 → disconnected, no retries. 413/422/pins/TLS → stopped with the message, no retries.
 * - Network and 5xx → [Outcome.RETRY] (WorkManager backoff).
 */
class BackupUploader(
    private val target: BackupTarget,
    private val readVault: () -> ByteArray?,
    private val status: () -> BackupStatus,
    private val update: ((BackupStatus) -> BackupStatus) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { NOTHING_TO_DO, SUCCESS, NEEDS_NEW_REV, RETRY, STOPPED }

    suspend fun run(): Outcome {
        val st = status()
        if (st.disconnected) return Outcome.STOPPED
        val raw = readVault() ?: return Outcome.NOTHING_TO_DO
        val header = try { Pvault2.peekHeader(raw) } catch (e: Exception) { return Outcome.NOTHING_TO_DO }
        val sha = HttpBackupTarget.sha256Hex(raw)
        if (st.lastSuccessRev == header.rev && st.lastSuccessSha == sha) return Outcome.NOTHING_TO_DO
        return try {
            when (val r = target.upload(header.vaultId, header.rev, raw)) {
                is UploadResult.Stored -> verify(header.vaultId, header.rev, sha, r.meta)
                UploadResult.Exists -> verify(header.vaultId, header.rev, sha, null)
            }
        } catch (e: BackupException) {
            update {
                it.copy(lastError = e.text, disconnected = it.disconnected || e.kind == BackupException.Kind.DISCONNECTED,
                    stopped = !e.retryable)
            }
            if (e.retryable) Outcome.RETRY else Outcome.STOPPED
        }
    }

    private suspend fun verify(vaultId: String, rev: Long, sha: String, stored: RemoteRev?): Outcome {
        val remote = target.list(vaultId).firstOrNull { it.rev == rev }
        return when {
            remote != null && remote.sha256 == sha && (stored == null || stored.sha256 == sha) -> {
                update {
                    it.copy(lastSuccessAt = clock(), lastSuccessRev = rev,
                        lastSuccessSha = sha, lastError = null, stopped = false, disconnected = false, needsNewRev = false)
                }
                Outcome.SUCCESS
            }
            stored == null && remote != null -> {
                update { it.copy(needsNewRev = true, lastError = "The server already has a different revision $rev. " +
                    "A new revision is saved and uploaded when you next unlock.", stopped = false) }
                Outcome.NEEDS_NEW_REV
            }
            else -> {
                // Stored (201) but not listed with our hash: treat as a transient server problem.
                update { it.copy(lastError = "The server did not confirm the upload. Trying again later.", stopped = false) }
                Outcome.RETRY
            }
        }
    }
}
