package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.backup.BackupErrors
import com.spirtosan.mobilevault.backup.BackupException
import com.spirtosan.mobilevault.backup.BackupHealth
import com.spirtosan.mobilevault.backup.BackupHealth.Level
import com.spirtosan.mobilevault.backup.BackupStatus
import com.spirtosan.mobilevault.backup.BackupTarget
import com.spirtosan.mobilevault.backup.BackupUploader
import com.spirtosan.mobilevault.backup.BackupUploader.Outcome
import com.spirtosan.mobilevault.backup.HttpBackupTarget
import com.spirtosan.mobilevault.backup.RemoteRev
import com.spirtosan.mobilevault.backup.UploadResult
import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.UnlockedVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** In-memory append-only server, like mvault: PUT never overwrites. */
class FakeTarget : BackupTarget {
    val files = sortedMapOf<Long, ByteArray>()
    var failWith: BackupException? = null
    var uploads = 0

    private fun meta(rev: Long, b: ByteArray) = RemoteRev(rev, b.size.toLong(), HttpBackupTarget.sha256Hex(b), "2026-11-03T00:00:00Z")
    private fun check() { failWith?.let { throw it } }

    /** null: behave like a server older than Task 08 (404 on GET /vaults). */
    var vaultList: List<com.spirtosan.mobilevault.backup.RemoteVault>? = emptyList()
    override suspend fun health() = check()
    override suspend fun vaults() = run { check(); vaultList ?: throw BackupErrors.fromHttp(404, "{\"error\": \"not found\"}") }
    override suspend fun upload(vaultId: String, rev: Long, bytes: ByteArray): UploadResult {
        check(); uploads++
        if (files.containsKey(rev)) return UploadResult.Exists
        files[rev] = bytes
        return UploadResult.Stored(meta(rev, bytes))
    }
    override suspend fun list(vaultId: String) = run { check(); files.map { (r, b) -> meta(r, b) } }
    override suspend fun download(vaultId: String, rev: Long) = run { check(); files[rev] ?: throw BackupErrors.fromHttp(404, null) }
    override suspend fun latest(vaultId: String) = run { check(); files.entries.lastOrNull()?.let { meta(it.key, it.value) } }
}

class BackupUploadTest {
    private val fast = Argon2Params(mKiB = 32768, t = 2, p = 4)
    private var status = BackupStatus()
    private var file: ByteArray? = null
    private val target = FakeTarget()
    private val uploader = BackupUploader(target, { file }, { status }, { status = it(status) }, clock = { 1_000L })

    private fun newVault(): UnlockedVault = Pvault2.create("pw".toCharArray(), "t", kdf = fast).let { file = it.bytes; it.vault }

    @Test fun storedThenVerified() = runBlocking {
        newVault()
        assertEquals(Outcome.SUCCESS, uploader.run())
        assertEquals(1L, status.lastSuccessRev)
        assertEquals(1_000L, status.lastSuccessAt)
        assertNull(status.lastError)
        assertEquals(Outcome.NOTHING_TO_DO, uploader.run())      // same file: no second PUT
        assertEquals(1, target.uploads)
    }

    @Test fun conflictWithSameShaIsSuccess() = runBlocking {
        newVault()
        target.files[1] = file!!                                  // e.g. uploaded before, status lost
        assertEquals(Outcome.SUCCESS, uploader.run())
        assertEquals(1L, status.lastSuccessRev)
        assertFalse(status.needsNewRev)
    }

    @Test fun conflictWithOtherFileNeedsNewRevision() = runBlocking {
        val v = newVault()
        file = v.seal("t")                                        // rev 2 locally
        target.files[2] = Pvault2.create("x".toCharArray(), "other", kdf = fast).vault.seal("o")   // another rev-2 file
        assertEquals(Outcome.NEEDS_NEW_REV, uploader.run())
        assertTrue(status.needsNewRev)
        assertEquals(0L, status.lastSuccessRev)
        // what the app does at the next unlock: re-seal as max(local, remote) + 1, upload again
        file = v.seal("t", atLeast = maxOf(v.rev, target.files.lastKey()) + 1)
        assertEquals(Outcome.SUCCESS, uploader.run())
        assertEquals(3L, status.lastSuccessRev)
        assertFalse(status.needsNewRev)
        assertEquals(setOf(2L, 3L), target.files.keys)            // nothing overwritten
    }

    @Test fun unauthorizedStopsAndMarksDisconnected() = runBlocking {
        newVault()
        target.failWith = BackupErrors.fromHttp(403, """{"error":"device revoked"}""")
        assertEquals(Outcome.STOPPED, uploader.run())
        assertTrue(status.disconnected)
        target.failWith = null
        assertEquals(Outcome.STOPPED, uploader.run())             // no retries until paired again
        assertEquals(0, target.uploads)
    }

    @Test fun networkErrorRetriesThenSucceeds() = runBlocking {
        newVault()
        target.failWith = BackupErrors.fromIo(UnknownHostException("vault.example.org"))
        assertEquals(Outcome.RETRY, uploader.run())
        assertFalse(status.stopped)
        assertTrue(status.lastError!!.contains("No connection"))
        target.failWith = null
        assertEquals(Outcome.SUCCESS, uploader.run())
        assertNull(status.lastError)
    }

    @Test fun tooLargeStopsWithMessage() = runBlocking {
        newVault()
        target.failWith = BackupErrors.fromHttp(413, "")
        assertEquals(Outcome.STOPPED, uploader.run())
        assertTrue(status.stopped)
        assertFalse(status.disconnected)
        assertTrue(status.lastError!!.contains("too large"))
    }

    @Test fun noVaultFileDoesNothing() = runBlocking {
        assertEquals(Outcome.NOTHING_TO_DO, uploader.run())
    }

    @Test fun ioExceptionIsNotSwallowed() {
        assertTrue(BackupErrors.fromIo(IOException("reset")).retryable)
    }
}

class BackupHealthTest {
    private val h = 3_600_000L
    private val d = 24 * h
    private val now = 100 * d
    private fun ok(agoMs: Long, rev: Long = 5) = BackupStatus(lastSuccessAt = now - agoMs, lastSuccessRev = rev)

    @Test fun thresholds() {
        assertEquals(Level.RED, BackupHealth.of(false, BackupStatus(), 5, now).level)
        assertEquals("No backup set up", BackupHealth.of(false, BackupStatus(), 5, now).text)
        BackupHealth.of(true, ok(2 * h), 5, now).let { assertEquals(Level.GREEN, it.level); assertEquals("Backed up 2 h ago", it.text) }
        assertEquals(Level.GREEN, BackupHealth.of(true, ok(3 * d - 1), 5, now).level)
        BackupHealth.of(true, ok(3 * d), 5, now).let { assertEquals(Level.AMBER, it.level); assertEquals("Not backed up for 3 days", it.text) }
        assertEquals(Level.AMBER, BackupHealth.of(true, ok(7 * d - 1), 5, now).level)
        assertEquals(Level.RED, BackupHealth.of(true, ok(7 * d), 5, now).level)
        assertEquals(Level.AMBER, BackupHealth.of(true, BackupStatus(), 1, now).level)           // paired, first upload pending
    }

    @Test fun errorsAndDisconnectAreRed() {
        assertEquals(Level.RED, BackupHealth.of(true, ok(h).copy(stopped = true, lastError = "too large"), 5, now).level)
        assertEquals(Level.GREEN, BackupHealth.of(true, ok(h).copy(lastError = "No connection"), 5, now).level)   // retrying
        assertEquals(Level.RED, BackupHealth.of(true, ok(h).copy(disconnected = true), 5, now).level)
    }

    @Test fun unsyncedChanges() {
        assertTrue(BackupHealth.of(true, ok(h, rev = 4), 5, now).unsynced)
        assertFalse(BackupHealth.of(true, ok(h, rev = 5), 5, now).unsynced)
        assertFalse(BackupHealth.of(false, BackupStatus(), 5, now).unsynced)
    }

    @Test fun agoTexts() {
        assertEquals("just now", BackupHealth.ago(30_000))
        assertEquals("5 min ago", BackupHealth.ago(5 * 60_000))
        assertEquals("1 day ago", BackupHealth.ago(d + h))
        assertEquals("3 days ago", BackupHealth.ago(3 * d))
    }
}
