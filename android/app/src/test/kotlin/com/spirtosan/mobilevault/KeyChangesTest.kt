package com.spirtosan.mobilevault

import com.spirtosan.mobilevault.backup.Restore
import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Entry
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.RecoveryKey
import com.spirtosan.mobilevault.crypto.VaultFormatException
import com.spirtosan.mobilevault.crypto.VaultPayload
import com.spirtosan.mobilevault.crypto.WrongKeyException
import com.spirtosan.mobilevault.security.KeyChanges
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The three key changes of Task 08 (code review F1): each one rotates the DEK where it should. */
class KeyChangesTest {
    private val fast = Argon2Params(mKiB = 32768, t = 2, p = 4)

    private fun vault(withKey: Boolean = true) = Pvault2.create("pw".toCharArray(), "t", kdf = fast, withRecoveryKey = withKey,
        payload = VaultPayload(entries = listOf(Entry(service = "S", password = "p"))))

    private inline fun <reified T : Throwable> throws(block: () -> Unit) {
        try { block(); fail("expected ${T::class.simpleName}") } catch (e: Throwable) { if (e !is T) throw e }
    }

    @Test fun newRecoveryKeyRotatesAndChecksPassword() {
        val c = vault()
        val v = c.vault
        val oldDek = v.exportDekForBiometric()
        throws<KeyChanges.WrongPassword> { KeyChanges.newRecoveryKey(v, "nope".toCharArray(), fast) }
        assertTrue(Pvault2.verifyWithDek(v.seal("t"), oldDek))           // a wrong password changed nothing

        val rk = KeyChanges.newRecoveryKey(v, "pw".toCharArray(), fast)
        val raw = v.seal("t")
        assertFalse(Pvault2.verifyWithDek(raw, oldDek))
        Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(rk.display()))
        Pvault2.unlockWithPassword(raw, "pw".toCharArray())
        throws<WrongKeyException> { Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(c.recoveryKey!!.display())) }
    }

    @Test fun createFirstRecoveryKeyAlsoRotates() {
        val v = vault(withKey = false).vault
        val oldDek = v.exportDekForBiometric()
        val rk = KeyChanges.newRecoveryKey(v, "pw".toCharArray(), fast)
        val raw = v.seal("t")
        assertTrue(Pvault2.peekHeader(raw).hasRecoveryKey)
        assertFalse(Pvault2.verifyWithDek(raw, oldDek))
        assertEquals("S", Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(rk.display())).payload.entries.single().service)
    }

    @Test fun removeRecoveryKeyRotatesAndChecksPassword() {
        val c = vault()
        val v = c.vault
        val oldDek = v.exportDekForBiometric()
        throws<KeyChanges.WrongPassword> { KeyChanges.removeRecoveryKey(v, "nope".toCharArray(), fast) }
        assertTrue(v.header.hasRecoveryKey)
        KeyChanges.removeRecoveryKey(v, "pw".toCharArray(), fast)
        val raw = v.seal("t")
        assertFalse(Pvault2.peekHeader(raw).hasRecoveryKey)
        assertFalse(Pvault2.verifyWithDek(raw, oldDek))
        Pvault2.unlockWithPassword(raw, "pw".toCharArray())
        throws<VaultFormatException> { Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(c.recoveryKey!!.display())) }
    }

    @Test fun changePasswordKeepsDekByDefault() {
        val c = vault()
        val v = c.vault
        val oldDek = v.exportDekForBiometric()
        assertNull(KeyChanges.changePassword(v, "new".toCharArray(), "a hint", fast, rotate = false))
        val raw = v.seal("t")
        assertTrue(Pvault2.verifyWithDek(raw, oldDek))                  // fingerprint slot still valid
        Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(c.recoveryKey!!.display()))   // kit still works
        Pvault2.unlockWithPassword(raw, "new".toCharArray())
        assertEquals("a hint", Pvault2.peekHeader(raw).hint)
    }

    @Test fun changePasswordWithRotation() {
        val c = vault()
        val v = c.vault
        val oldDek = v.exportDekForBiometric()
        val rk = KeyChanges.changePassword(v, "new".toCharArray(), "a hint", fast, rotate = true)
        assertNotNull(rk)                                               // had a recovery key: a new one + kit
        val raw = v.seal("t")
        assertFalse(Pvault2.verifyWithDek(raw, oldDek))
        throws<WrongKeyException> { Pvault2.unlockWithPassword(raw, "pw".toCharArray()) }
        throws<WrongKeyException> { Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(c.recoveryKey!!.display())) }
        Pvault2.unlockWithPassword(raw, "new".toCharArray())
        Pvault2.unlockWithRecoveryKey(raw, RecoveryKey.parse(rk!!.display()))
        assertEquals("a hint", Pvault2.peekHeader(raw).hint)

        // without a recovery key: rotation, but no new key
        val w = vault(withKey = false).vault
        assertNull(KeyChanges.changePassword(w, "new".toCharArray(), null, fast, rotate = true))
        assertFalse(Pvault2.peekHeader(w.seal("t")).hasRecoveryKey)
    }

    @Test fun badHintChangesNothing() {
        val v = vault().vault
        val oldDek = v.exportDekForBiometric()
        throws<IllegalArgumentException> { KeyChanges.changePassword(v, "Secret99".toCharArray(), "secret99", fast, rotate = true) }
        val raw = v.seal("t")
        assertTrue(Pvault2.verifyWithDek(raw, oldDek))
        Pvault2.unlockWithPassword(raw, "pw".toCharArray())
    }

    /** Settings → "Check my recovery key" (Task 09). */
    @Test fun checkRecoveryKey() {
        val c = vault()
        val raw = c.bytes
        val shown = c.recoveryKey!!.display()
        assertEquals(KeyChanges.KeyCheck.OPENS, KeyChanges.checkRecoveryKey(raw, shown))
        assertEquals(KeyChanges.KeyCheck.OPENS, KeyChanges.checkRecoveryKey(raw, shown.lowercase().replace("-", " ")))
        assertEquals(KeyChanges.KeyCheck.WRONG, KeyChanges.checkRecoveryKey(raw, RecoveryKey.generate().display()))
        throws<IllegalArgumentException> { KeyChanges.checkRecoveryKey(raw, "ABCD") }
        assertEquals(KeyChanges.KeyCheck.NO_RECOVERY_SLOT, KeyChanges.checkRecoveryKey(vault(withKey = false).bytes, shown))
        // after a new key the old one no longer opens the new file
        val v = c.vault
        val newKey = KeyChanges.newRecoveryKey(v, "pw".toCharArray(), fast).display()
        val raw2 = v.seal("t")
        assertEquals(KeyChanges.KeyCheck.WRONG, KeyChanges.checkRecoveryKey(raw2, shown))
        assertEquals(KeyChanges.KeyCheck.OPENS, KeyChanges.checkRecoveryKey(raw2, newKey))
    }

    /** Right after a rotation the server's newest file still has the old DEK: "still uploading", not a failure. */
    @Test fun testRestoreRightAfterRotationSaysStillUploading() = runBlocking {
        val target = FakeTarget()
        val c = vault()
        val v = c.vault
        target.files[1] = c.bytes
        KeyChanges.newRecoveryKey(v, "pw".toCharArray(), fast)
        val local = v.seal("t")                                        // rev 2, not uploaded yet
        val r = Restore.testRestore(target, v.vaultId, { v.exportDekForBiometric() }, 1, localRev = 2)
        assertFalse(r.ok)
        assertEquals(Restore.STILL_UPLOADING, r.text)
        target.files[2] = local
        assertTrue(Restore.testRestore(target, v.vaultId, { v.exportDekForBiometric() }, 1, localRev = 2).ok)
        // same rev but another key is a real failure
        val other = Restore.testRestore(target, v.vaultId, { ByteArray(32) }, 1, localRev = 2)
        assertFalse(other.ok)
        assertTrue(other.text.startsWith("The latest backup"))
    }
}
