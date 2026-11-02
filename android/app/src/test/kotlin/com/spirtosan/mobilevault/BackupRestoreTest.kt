package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.backup.BackupException
import com.spirtosan.mobilevault.backup.BackupStatus
import com.spirtosan.mobilevault.backup.BackupUploader
import com.spirtosan.mobilevault.backup.BackupUploader.Outcome
import com.spirtosan.mobilevault.backup.Restore
import com.spirtosan.mobilevault.backup.TestRestoreReminder
import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.UnlockedVault
import com.spirtosan.mobilevault.crypto.VaultPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupRestoreTest {
    private val fast = Argon2Params(mKiB = 32768, t = 2, p = 4)
    private val target = FakeTarget()

    /** A vault whose revs 1..n are on the fake server, entry count = rev. */
    private fun vaultOnServer(n: Int): Pair<UnlockedVault, String> {
        val c = Pvault2.create("pw".toCharArray(), "t", kdf = fast, payload = VaultPayload(entries = listOf(Entry(service = "e1", password = "p"))))
        val v = c.vault
        target.files[1] = c.bytes
        for (r in 2..n) {
            v.payload = v.payload.copy(entries = v.payload.entries + Entry(service = "e$r", password = "p"))
            target.files[r.toLong()] = v.seal("t")
        }
        return v to v.vaultId
    }

    @Test fun vaultIdIsNormalised() {
        val id = "1a2b3c4d-1111-2222-3333-444455556666"
        assertEquals(id, Restore.normalizeVaultId("  Vault ID: ${id.uppercase()} "))
        assertNull(Restore.normalizeVaultId("1a2b3c4d"))
        assertNull(Restore.normalizeVaultId("1a2b3c4d-1111-2222-3333-44445555666g"))
    }

    @Test fun newestFirstAndRightFile() = runBlocking {
        val (_, id) = vaultOnServer(4)
        val revs = Restore.newestFirst(target.list(id))
        assertEquals(listOf(4L, 3L, 2L, 1L), revs.map { it.rev })
        val raw = Restore.fetch(target, id, 3)
        assertEquals(3L, Pvault2.peekHeader(raw).rev)
        assertEquals(3, Pvault2.unlockWithPassword(raw, "pw".toCharArray()).payload.activeEntries.size)
    }

    @Test fun wrongVaultIdOrRevIsRefused() {
        val a = Pvault2.create("pw".toCharArray(), "t", kdf = fast)
        val other = "00000000-0000-4000-8000-000000000000"
        for ((id, rev) in listOf(other to 1L, a.vault.vaultId to 2L)) {
            try { Restore.check(a.bytes, id, rev); fail("accepted $id r$rev") } catch (e: BackupException) {
                assertEquals(BackupException.Kind.REJECTED, e.kind)
            }
        }
        try { Restore.check(ByteArray(40), a.vault.vaultId, 1); fail() } catch (e: BackupException) { /* ok */ }
        assertEquals(1L, Restore.check(a.bytes, a.vault.vaultId, 1).rev)
    }

    @Test fun rollbackSavesOldContentAsNewRevisionAboveEverything() = runBlocking {
        val (v, id) = vaultOnServer(5)
        val dek = { v.exportDekForBiometric() }
        val old = Restore.preview(Restore.fetch(target, id, 2), dek())!!
        assertEquals(2, old.entries)
        val newRev = Restore.rollbackRev(localRev = v.rev, remoteLatest = target.latest(id)!!.rev)
        assertEquals(6L, newRev)
        v.payload = old.payload
        val sealed = v.seal("t", atLeast = newRev)
        assertEquals(6L, Pvault2.peekHeader(sealed).rev)
        // the upload of the rolled-back vault is a normal new revision: no 409, nothing overwritten
        var status = BackupStatus(lastSuccessRev = 5)
        val up = BackupUploader(target, { sealed }, { status }, { status = it(status) })
        assertEquals(Outcome.SUCCESS, up.run())
        assertEquals(6L, status.lastSuccessRev)
        assertEquals((1L..6L).toList(), target.files.keys.toList())
    }

    @Test fun conflictAfterRollbackOnAnotherDeviceIsResolvedWithHigherRev() = runBlocking {
        val (v, id) = vaultOnServer(3)
        // this phone restored rev 1 locally (local rev 1 → next seal 2), but the server has 2 and 3
        var status = BackupStatus()
        var file = v.seal("t")                                     // rev 4 normally …
        val stale = Pvault2.unlockWithPassword(target.files[1]!!, "pw".toCharArray())
        file = stale.seal("t")                                     // … the stale copy makes a different rev 2
        val up = BackupUploader(target, { file }, { status }, { status = it(status) })
        assertEquals(Outcome.NEEDS_NEW_REV, up.run())
        file = stale.seal("t", atLeast = Restore.rollbackRev(stale.rev, target.latest(id)!!.rev))
        assertEquals(Outcome.SUCCESS, up.run())
        assertTrue(status.lastSuccessRev > 3)
        assertFalse(status.needsNewRev)
    }

    @Test fun previewNeedsTheRightKey() {
        val a = Pvault2.create("pw".toCharArray(), "t", kdf = fast)
        val b = Pvault2.create("pw".toCharArray(), "t", kdf = fast)
        assertNull(Restore.preview(a.bytes, b.vault.exportDekForBiometric()))
        assertEquals(1L, Restore.preview(a.bytes, a.vault.exportDekForBiometric())!!.rev)
    }

    @Test fun testRestoreDecryptsLatestAndComparesCounts() = runBlocking {
        val (v, id) = vaultOnServer(3)
        val same = Restore.testRestore(target, id, { v.exportDekForBiometric() }, localEntries = 3)
        assertTrue(same.ok); assertTrue(same.text, same.text.contains("revision 3") && same.text.contains("same as on this phone"))
        val diff = Restore.testRestore(target, id, { v.exportDekForBiometric() }, localEntries = 4)
        assertTrue(diff.ok); assertTrue(diff.text.contains("this phone has 4"))
        val wrongKey = Restore.testRestore(target, id, { ByteArray(32) }, 3)
        assertFalse(wrongKey.ok)
        val none = Restore.testRestore(FakeTarget(), id, { v.exportDekForBiometric() }, 3)
        assertFalse(none.ok)
    }

    @Test fun testRestoreReminderEvery30Days() {
        val d = 24 * 3_600_000L
        val backed = BackupStatus(lastSuccessAt = 10 * d)
        assertFalse(TestRestoreReminder.due(true, backed, pairedAt = 0, dismissedAt = 0, now = 29 * d))
        assertTrue(TestRestoreReminder.due(true, backed, pairedAt = 0, dismissedAt = 0, now = 30 * d))
        assertFalse(TestRestoreReminder.due(true, backed.copy(testRestoreAt = 20 * d), 0, 0, now = 40 * d))
        assertFalse(TestRestoreReminder.due(true, backed, 0, dismissedAt = 25 * d, now = 40 * d))
        assertFalse(TestRestoreReminder.due(true, BackupStatus(), 0, 0, now = 99 * d))     // nothing backed up yet
        assertFalse(TestRestoreReminder.due(false, backed, 0, 0, now = 99 * d))
    }

}
