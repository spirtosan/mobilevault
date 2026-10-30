package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.data.Snapshot
import com.spirtosan.mobilevault.data.SnapshotPolicy
import com.spirtosan.mobilevault.data.VaultEdits
import com.spirtosan.mobilevault.data.VaultRepository
import com.spirtosan.mobilevault.security.KdfCalibration
import com.spirtosan.mobilevault.security.PasswordGenerator
import com.spirtosan.mobilevault.security.PasswordStrength
import com.spirtosan.mobilevault.security.UnlockThrottle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

private val FAST = Argon2Params(mKiB = 32768, t = 2, p = 4)   // the minimum; creation refuses less

class VaultRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vault(n: Int) = Pvault2.create("pw".toCharArray(), "test", kdf = FAST,
        payload = VaultPayload(entries = List(n) { Entry(service = "s$it", password = "p$it") }))

    @Test
    fun atomicSave_writesFileAndLeavesNoTemp() {
        val repo = VaultRepository(tmp.root)
        val c = vault(1)
        repo.save(c.bytes)
        assertArrayEquals(c.bytes, repo.read())
        assertFalse(File(tmp.root, "vault.pvault.tmp").exists())
        assertEquals("first save has nothing to snapshot", 0, repo.snapshots().size)
        val opened = Pvault2.unlockWithPassword(repo.read(), "pw".toCharArray())
        assertEquals(1, opened.payload.entries.size)
    }

    @Test
    fun saveSnapshotsThePreviousRevision() {
        var now = Instant.parse("2026-10-30T10:00:00Z")
        val repo = VaultRepository(tmp.root) { now }
        val c = vault(1)
        repo.save(c.bytes)
        c.vault.payload = c.vault.payload.copy(entries = c.vault.payload.entries + Entry(service = "x", password = "y"))
        now = now.plusSeconds(60)
        repo.save(c.vault.seal("test"))
        val snaps = repo.snapshots()
        assertEquals(1, snaps.size)
        assertEquals(1L, snaps.single().rev)
        assertArrayEquals(c.bytes, snaps.single().file.readBytes())
        assertEquals(2L, Pvault2.peekHeader(repo.read()).rev)
    }

    @Test
    fun refusesNonVaultBytes() {
        val repo = VaultRepository(tmp.root)
        assertTrue(runCatching { repo.save("plaintext".toByteArray()) }.isFailure)
        assertFalse(repo.exists())
    }

    @Test
    fun retentionKeepsLast20AndOnePerDayFor30Days() {
        val now = Instant.parse("2026-10-30T12:00:00Z")
        // 3 snapshots a day for 40 days
        val all = (0 until 40).flatMap { d ->
            (0 until 3).map { h ->
                val t = now.minus(Duration.ofDays(d.toLong())).minus(Duration.ofHours(h.toLong()))
                Snapshot(File("x-$d-$h"), t, 1000L - d * 3 - h)
            }
        }
        val keep = SnapshotPolicy.keep(all, now)
        val newest20 = all.sortedByDescending { it.time }.take(20)
        assertTrue(keep.containsAll(newest20))
        // every one of the last 30 days keeps its newest snapshot (h = 0)
        (0 until 30).forEach { d -> assertTrue("day $d", keep.any { it.file.name == "x-$d-0" }) }
        // nothing older than 30 days
        assertTrue(keep.none { it.file.name.split("-")[1].toInt() >= 30 })
        assertEquals(20 + (30 - 7), keep.size)   // days 0-6 are fully inside the newest 20 (7×3 = 21 ≥ 20)
    }

    @Test
    fun saveRealPruning() {
        var now = Instant.parse("2026-10-30T10:00:00Z")
        val repo = VaultRepository(tmp.root) { now }
        val c = vault(0)
        repo.save(c.bytes)
        repeat(30) { now = now.plusSeconds(1); repo.save(c.vault.seal("t")) }
        assertEquals(SnapshotPolicy.LAST, repo.snapshots().size)
    }
}

class VaultEditsTest {
    private val a = Entry(service = "GitHub", username = "me", password = "1", modified = "2026-01-01T00:00:00Z")
    private val b = Entry(service = "bank", username = "me", password = "2", url = "https://bank.example", modified = "2026-02-01T00:00:00Z")
    private val c = Entry(service = "Zoo", username = "x", password = "3", favourite = true, modified = "2025-01-01T00:00:00Z")
    private val p = VaultPayload(entries = listOf(a, b, c))

    @Test
    fun deleteIsTombstone() {
        val r = VaultEdits.delete(p, b.id, "2026-10-30T00:00:00Z")
        val t = r.entries.single { it.id == b.id }
        assertTrue(t.deleted)
        assertEquals("2026-10-30T00:00:00Z", t.modified)
        assertEquals(3, r.entries.size)
        assertEquals(2, r.activeEntries.size)
    }

    @Test
    fun upsertUpdatesModified() {
        val r = VaultEdits.upsert(p, a.copy(password = "new"), "2026-10-30T00:00:00Z")
        assertEquals("new", r.entries.single { it.id == a.id }.password)
        assertEquals("2026-10-30T00:00:00Z", r.entries.single { it.id == a.id }.modified)
        val added = VaultEdits.upsert(p, Entry(service = "n", password = "p"), "2026-10-30T00:00:00Z")
        assertEquals(4, added.entries.size)
    }

    @Test
    fun importMergeSkipsExactDuplicates() {
        val incoming = listOf(
            a.copy(id = "other-id"),                       // same service+user+password → skip
            a.copy(id = "id2", password = "changed"),      // different password → add
            Entry(service = "New", password = "x"),
            Entry(service = "New", password = "x"),        // duplicate inside the import → skip
            Entry(service = "Gone", password = "x", deleted = true),
        )
        val r = VaultEdits.mergeImport(p, incoming)
        assertEquals(2, r.added)
        assertEquals(2, r.skipped)
        assertEquals(5, r.payload.entries.size)
    }

    @Test
    fun deletedEntryDoesNotBlockReimport() {
        val r = VaultEdits.mergeImport(VaultEdits.delete(p, a.id), listOf(a.copy(id = "fresh")))
        assertEquals(1, r.added)
    }

    @Test
    fun searchAndSort() {
        // Favourites first; A–Z ignores case; "recent" = newest modified first.
        assertEquals(listOf("Zoo", "bank", "GitHub"), VaultEdits.visible(p, "", VaultEdits.Sort.AZ).map { it.service })
        val newerA = VaultEdits.upsert(p, a, "2026-10-30T00:00:00Z")
        assertEquals(listOf("Zoo", "GitHub", "bank"), VaultEdits.visible(newerA, "", VaultEdits.Sort.RECENT).map { it.service })
        assertEquals(listOf("bank"), VaultEdits.visible(p, "bank.EXAMPLE", VaultEdits.Sort.AZ).map { it.service })
        assertEquals(listOf("bank", "GitHub"), VaultEdits.visible(p, "me", VaultEdits.Sort.AZ).map { it.service })
        assertTrue(VaultEdits.visible(VaultEdits.delete(p, c.id), "zoo", VaultEdits.Sort.AZ).isEmpty())
    }
}

class SecurityHelpersTest {
    @Test
    fun strength() {
        fun lvl(s: String) = PasswordStrength.score(s.toCharArray()).level
        assertEquals(0, lvl("password123"))
        assertEquals(0, lvl("aaaaaaaaaaaa"))
        assertTrue(lvl("abcdefgh") <= 1)
        assertTrue(lvl("Tr0ub4dor&3") >= 2)
        assertEquals(4, lvl("correct-horse-battery-staple-xk"))
        assertEquals(0, PasswordStrength.score(CharArray(0)).bits)
    }

    @Test
    fun generatorRespectsOptions() {
        val g = PasswordGenerator({ List(1296) { "w$it" } })
        repeat(50) {
            val pw = g.generate(PasswordGenerator.Options(length = 16, symbols = false))
            assertEquals(16, pw.length)
            assertTrue(pw.any { it.isLowerCase() } && pw.any { it.isUpperCase() } && pw.any { it.isDigit() })
            assertTrue(pw.all { it.isLetterOrDigit() })
        }
        assertEquals(64, g.generate(PasswordGenerator.Options(length = 64)).length)
        val pp = g.generate(PasswordGenerator.Options(passphrase = true, words = 6))
        assertEquals(6, pp.split("-").size)
        assertTrue(runCatching { g.generate(PasswordGenerator.Options(length = 8)) }.isFailure)
    }

    @Test
    fun throttle() {
        assertEquals(0, UnlockThrottle.delayMs(4))
        assertEquals(30_000, UnlockThrottle.delayMs(5))
        assertEquals(60_000, UnlockThrottle.delayMs(6))
        assertEquals(15 * 60_000, UnlockThrottle.delayMs(40))
        assertEquals(10_000, UnlockThrottle.remainingMs(5, 1_000_000, 1_020_000))
        assertEquals(0, UnlockThrottle.remainingMs(5, 1_000_000, 2_000_000))
    }

    @Test
    fun calibrationPicksStrongestWithinBudget() {
        val fast = KdfCalibration.choose { 130 }
        assertEquals(Argon2Params(65536, 3, 4), fast.params)
        val mid = KdfCalibration.choose { p -> if (p.mKiB == 65536) 1500 else 700 }
        assertEquals(Argon2Params(32768, 3, 4), mid.params)
        val slow = KdfCalibration.choose { 5000 }
        assertEquals("never below 32 MiB / t=2", Argon2Params(32768, 2, 4), slow.params)
        assertTrue(KdfCalibration.CANDIDATES.all { it.p == 4 })
    }
}
