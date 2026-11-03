package com.spirtosan.mobilevault.security

import com.spirtosan.mobilevault.crypto.Argon2Params
import com.spirtosan.mobilevault.crypto.Pvault2
import com.spirtosan.mobilevault.crypto.VaultFormatException
import com.spirtosan.mobilevault.crypto.WrongKeyException
import com.spirtosan.mobilevault.crypto.RecoveryKey
import com.spirtosan.mobilevault.crypto.UnlockedVault

/**
 * Recovery-key and password changes that rotate the vault key (DEK), code review F1 / Task 08.
 * Run on the open vault inside `Session.withVault(save = true)`; the next seal uses the new DEK.
 * Older copies (server revisions, local snapshots) keep opening with the old key or password.
 */
object KeyChanges {
    class WrongPassword : Exception("Master password is wrong")

    const val BIOMETRIC_OFF = "Fingerprint unlock was turned off because the vault key changed. Turn it on again in Settings → Security."
    const val NEW_KEY_DONE = "New recovery key saved. Newer versions of the vault open only with this key; " +
        "older backups and local snapshots still open with the old one."
    const val REMOVED_DONE = "Recovery key removed. Newer versions of the vault open only with the master password; " +
        "older backups and local snapshots still open with the old key."
    const val PASSWORD_ROTATED_DONE = "Master password and vault key changed. Newer versions don't open with the old password; " +
        "older backups and local snapshots still do."

    /** Shown before a new recovery key is made (Settings, the reminder cards). */
    const val NEW_KEY_CONFIRM = "This creates a new recovery key and a new vault key. Your current key and printed Emergency Kit " +
        "won't open newer versions of the vault. Older backups on the server and local snapshots still open with the old " +
        "key or password, but they only contain old data.\n\nEnter your master password to continue."
    const val REMOVE_CONFIRM = "Without a recovery key, a forgotten master password means all passwords in this vault are lost.\n\n" +
        "The vault key is changed too, so your printed Emergency Kit won't open newer versions. Older backups on the server " +
        "and local snapshots still open with it."

    enum class KeyCheck { OPENS, WRONG, NO_RECOVERY_SLOT }

    /**
     * Settings → "Check my recovery key" (Task 09): does [keyText] open the vault file [raw]? Only a
     * try-unlock in memory, the result is wiped; nothing changes. Throws IllegalArgumentException
     * (with a message for the owner) if the text isn't a well-formed recovery key.
     */
    fun checkRecoveryKey(raw: ByteArray, keyText: String): KeyCheck {
        val key = RecoveryKey.parse(keyText)
        return try {
            Pvault2.unlockWithRecoveryKey(raw, key).wipe()
            KeyCheck.OPENS
        } catch (e: WrongKeyException) {
            KeyCheck.WRONG
        } catch (e: VaultFormatException) {
            if (!Pvault2.peekHeader(raw).hasRecoveryKey) KeyCheck.NO_RECOVERY_SLOT else throw e
        } finally { key.wipe() }
    }

    /** "New recovery key" / "Create recovery key": check [password], rotate, return the new key (show it once). */
    fun newRecoveryKey(v: UnlockedVault, password: CharArray, kdf: Argon2Params): RecoveryKey {
        if (!v.verifyPassword(password)) throw WrongPassword()
        return v.rotateDek(password, newRecoveryKey = true, kdf = kdf)!!
    }

    /** "Remove recovery key": check [password], rotate without a recovery slot. */
    fun removeRecoveryKey(v: UnlockedVault, password: CharArray, kdf: Argon2Params) {
        if (!v.verifyPassword(password)) throw WrongPassword()
        v.rotateDek(password, newRecoveryKey = false, kdf = kdf)
    }

    /**
     * "Change master password". Without [rotate] only the password slot changes (the recovery key keeps
     * working). With [rotate] ("Someone may know my old password") the DEK changes as well; a vault that
     * had a recovery key gets a new one, which is returned (old slots can't be re-wrapped without the old key).
     */
    fun changePassword(v: UnlockedVault, newPassword: CharArray, hint: String?, kdf: Argon2Params, rotate: Boolean): RecoveryKey? {
        v.changePassword(newPassword, hint, kdf)            // checks the hint policy before anything changes
        if (!rotate) return null
        return v.rotateDek(newPassword, newRecoveryKey = v.header.hasRecoveryKey, kdf = kdf)
    }
}
