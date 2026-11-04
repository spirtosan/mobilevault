package com.spirtosan.mobilevault.backup

import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.VaultHeader
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.crypto.WrongKeyException
import com.spirtosan.mobilevault.crypto.VaultFormatException
import java.time.Instant
import java.time.OffsetDateTime

/** Restore, version history and test restore: the parts that don't touch Android. */
object Restore {
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Vault ID as typed or pasted (case, spaces and a `Vault ID:` prefix don't matter); null if invalid. */
    fun normalizeVaultId(text: String): String? =
        Regex("[0-9a-fA-F-]{36}").find(text.trim())?.value?.lowercase()?.takeIf { UUID_RE.matches(it) }

    /**
     * The vaults on the server for the restore screen, or null if the server can't list them
     * (older than Task 08: 404). Then the screen asks for the Vault ID instead.
     */
    suspend fun vaultsOrNull(target: BackupTarget): List<RemoteVault>? = try { target.vaults() } catch (e: BackupException) {
        if (e.kind == BackupException.Kind.NOT_FOUND) null else throw e
    }

    /** "Vault 1a2b3c4d…" */
    fun shortId(vaultId: String) = "Vault " + vaultId.take(8) + "…"

    fun newestFirst(revs: List<RemoteRev>) = revs.sortedByDescending { it.rev }

    /** Restore list (Task 13): most recent backup first; rows without a readable time go last. */
    fun mostRecentFirst(vaults: List<RemoteVault>): List<RemoteVault> = vaults.sortedWith(
        compareByDescending<RemoteVault> { runCatching { OffsetDateTime.parse(it.latestTime).toInstant() }.getOrNull() ?: Instant.MIN }
            .thenByDescending { it.latestRev })

    /** "Vault from xiaomi"; the short ID when the server has no device name. */
    fun vaultTitle(v: RemoteVault) = if (v.device.isBlank()) shortId(v.vaultId) else "Vault from ${v.device.trim()}"

    /**
     * "3 Nov 2026, 10:00 · 42 versions · ID 1a2b3c4d…": only what the server already knows (zero-knowledge:
     * no entry counts). The short ID stays so it can be matched with the Emergency Kit.
     */
    fun vaultDetails(v: RemoteVault, time: (String) -> String): String = listOfNotNull(
        v.latestTime.takeIf { it.isNotEmpty() }?.let(time),
        v.revisions.takeIf { it > 0 }?.let { if (it == 1) "1 version" else "$it versions" },
        "ID " + v.vaultId.take(8) + "…",
    ).joinToString(" · ")

    /** Download a revision and make sure it is the file we asked for (PVAULT2, same vault_id and rev). */
    suspend fun fetch(target: BackupTarget, vaultId: String, rev: Long): ByteArray =
        target.download(vaultId, rev).also { check(it, vaultId, rev) }

    fun check(raw: ByteArray, vaultId: String, rev: Long): VaultHeader {
        val h = try { Pvault2.peekHeader(raw) } catch (e: VaultFormatException) {
            throw BackupException(BackupException.Kind.REJECTED, "The downloaded file is not a Mobile Vault file.")
        }
        if (h.vaultId != vaultId) throw BackupException(BackupException.Kind.REJECTED,
            "The downloaded file belongs to another vault (${h.vaultId}). Nothing was changed.")
        if (h.rev != rev) throw BackupException(BackupException.Kind.REJECTED,
            "The downloaded file is revision ${h.rev}, not $rev. Nothing was changed.")
        return h
    }

    /** A restored old version is saved as a new revision above everything known: nothing is lost. */
    fun rollbackRev(localRev: Long, remoteLatest: Long) = maxOf(localRev, remoteLatest) + 1

    class Preview(val rev: Long, val updated: String, val payload: VaultPayload) {
        val entries get() = payload.activeEntries.size
    }

    /** Decrypt in memory with the open vault's DEK (a copy is consumed). Null if the DEK doesn't open it. */
    fun preview(raw: ByteArray, dekCopy: ByteArray): Preview? = try {
        val v = Pvault2.unlockWithDek(raw, dekCopy)
        val p = Preview(v.rev, v.header.updated, v.payload)
        v.wipe()
        p
    } catch (e: WrongKeyException) { null } catch (e: VaultFormatException) { null }

    class TestResult(val ok: Boolean, val text: String)

    const val STILL_UPLOADING = "The newest version is still uploading. Try again in a minute."

    /**
     * Test restore: download the latest revision, decrypt it in memory, compare the entry count. Writes nothing.
     * Right after a key change the server's newest file still has the old key: if it is older than [localRev],
     * that is "still uploading", not a failure.
     */
    suspend fun testRestore(target: BackupTarget, vaultId: String, dekCopy: suspend () -> ByteArray, localEntries: Int, localRev: Long = 0): TestResult {
        val latest = target.latest(vaultId) ?: return TestResult(false, "There is no backup of this vault on the server yet.")
        val raw = fetch(target, vaultId, latest.rev)
        val p = preview(raw, dekCopy()) ?: return TestResult(false,
            if (latest.rev < localRev) STILL_UPLOADING
            else "The latest backup (revision ${latest.rev}) could not be decrypted with this vault's key.")
        val same = p.entries == localEntries
        return TestResult(true, "✓ Backup revision ${latest.rev} decrypts: ${p.entries} entries" +
            if (same) ", same as on this phone." else " (this phone has $localEntries; the newest changes may still be uploading).")
    }
}
