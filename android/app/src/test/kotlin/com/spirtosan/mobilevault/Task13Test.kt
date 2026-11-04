package com.spirtosan.mobilevault

import androidx.biometric.BiometricManager
import com.spirtosan.mobilevault.backup.BackupErrors
import com.spirtosan.mobilevault.backup.BackupException.Kind
import com.spirtosan.mobilevault.backup.PairingPayload
import com.spirtosan.mobilevault.backup.RemoteVault
import com.spirtosan.mobilevault.backup.Restore
import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.Pvault1
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.ui.NONE_ENROLLED_TEXT
import com.spirtosan.mobilevault.ui.biometricReasonFor
import com.spirtosan.mobilevault.ui.unlockTimeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 13: user-facing texts, restore list, unlock time, fingerprint reasons, full copy. */
class Task13Test {
    // ── fix 1: no admin commands for the user in release ──
    @Test fun pairingTextsForUserAndAdmin() {
        val user = BackupErrors.fromHttp(403, null, pairing = true, admin = false)
        assertEquals(Kind.PAIR_CODE, user.kind)
        assertFalse(user.text.contains("mvault-admin"))
        assertTrue(user.text.contains("Ask the person who runs your backup server"))
        assertTrue(BackupErrors.fromHttp(403, null, pairing = true, admin = true).text.contains("mvault-admin pair"))
        assertTrue(PairingPayload.NO_PINS_TEXT.startsWith("This pairing QR code is incomplete"))
        assertTrue(PairingPayload.NO_PINS_TEXT.contains("Ask the person who runs your backup server"))
        val e = runCatching { PairingPayload.parse("hello") }.exceptionOrNull()
        assertEquals(PairingPayload.NOT_A_CODE, e?.message)
        assertFalse(PairingPayload.NOT_A_CODE.contains("mvault-admin"))
    }

    // ── fix 2 ──
    @Test fun unlockTimeInPlainWords() {
        assertEquals("Unlock takes less than a second on this phone.", unlockTimeText(127))
        assertEquals("Unlock takes about 1 second on this phone.", unlockTimeText(1200))
        assertEquals("Unlock takes about 3 seconds on this phone.", unlockTimeText(2600))
    }

    // ── fix 3 ──
    private fun v(id: String, time: String, device: String, revs: Int = 3, rev: Long = 3) = RemoteVault(id, rev, time, 1000, device, revs)

    @Test fun restoreListShowsDeviceAndDateMostRecentFirst() {
        val a = v("aaaaaaaa-0000-4000-8000-000000000001", "2026-11-01T10:00:00Z", "old-phone", revs = 42)
        val b = v("bbbbbbbb-0000-4000-8000-000000000002", "2026-11-03T09:00:00.5Z", "")
        val c = v("cccccccc-0000-4000-8000-000000000003", "", "tablet")
        val d = v("dddddddd-0000-4000-8000-000000000004", "2026-11-02T00:00:00+02:00", "laptop")
        // d = 1 Nov 22:00 UTC (written with an offset): between b and a; no time at all goes last
        assertEquals(listOf(b, d, a, c).map { it.vaultId }, Restore.mostRecentFirst(listOf(a, c, d, b)).map { it.vaultId })
        assertEquals("Vault from old-phone", Restore.vaultTitle(a))
        assertEquals("Vault aaaaaaaa…", Restore.vaultTitle(a.copy(device = " ")))
        assertEquals("Vault bbbbbbbb…", Restore.vaultTitle(b))
        assertEquals("T · 42 versions · ID aaaaaaaa…", Restore.vaultDetails(a) { "T" })
        assertEquals("1 version · ID cccccccc…", Restore.vaultDetails(c.copy(revisions = 1)) { "T" })
        assertEquals(c.vaultId, Restore.mostRecentFirst(listOf(c.copy(latestTime = "garbage"), a)).last().vaultId)
    }

    // ── fix 4 ──
    @Test fun fingerprintReasons() {
        assertNull(biometricReasonFor(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(NONE_ENROLLED_TEXT, biometricReasonFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertTrue(biometricReasonFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)!!.contains("Class 3"))
        assertTrue(NONE_ENROLLED_TEXT.contains("Settings → Fingerprint"))
    }

    // ── fix 5: the full copy is the PVAULT2 file itself ──
    @Test fun fullCopyKeepsEverythingAndOpensWithPasswordOrRecoveryKey() {
        var p = VaultPayload(entries = listOf(
            Entry(service = "Mail", username = "me", password = "p1", notes = "a note", tags = listOf("home", "mail"), favourite = true),
            Entry(service = "Bank", password = "p2"),
        ))
        val (p1, sec) = VaultEdits.addSection(p, "Personal")
        p = VaultEdits.moveEntries(p1, setOf(p1.entries[0].id), sec.id)
        val kdf = Argon2Params(mKiB = 32768, t = 2, p = 4)   // the minimum
        val created = Pvault2.create("master-pw-1".toCharArray(), "dev", null, p, kdf, withRecoveryKey = true)
        val copy = created.bytes.copyOf()               // what "Save a full copy" writes
        assertTrue(Pvault2.isPvault2(copy))
        assertFalse(Pvault1.isPvault1(copy))
        val byPw = Pvault2.unlockWithPassword(copy, "master-pw-1".toCharArray())
        assertEquals(created.vault.vaultId, byPw.vaultId)              // restore keeps the Vault ID
        assertEquals(p.entries, byPw.payload.entries)
        assertEquals(listOf("Personal"), VaultEdits.sections(byPw.payload).map { it.name })
        val e = byPw.payload.entries.first { it.service == "Mail" }
        assertEquals("a note", e.notes); assertEquals(listOf("home", "mail"), e.tags); assertTrue(e.favourite); assertEquals(sec.id, e.section)
        val byKey = Pvault2.unlockWithRecoveryKey(copy, created.recoveryKey!!)
        assertEquals(p.entries, byKey.payload.entries)
        assertTrue(runCatching { Pvault2.unlockWithPassword(copy, "wrong".toCharArray()) }.isFailure)
    }

    @Test fun desktopFileIsToldApart() {
        val desktop = Pvault1.encrypt("desktop-pw".toCharArray(), VaultPayload(entries = listOf(Entry(service = "x", password = "y"))), iterations = 1000)
        assertTrue(Pvault1.isPvault1(desktop))
        assertFalse(Pvault2.isPvault2(desktop))
    }
}
