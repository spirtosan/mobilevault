# Task 08 for Claude Code: fixes from code review 01 + `GET /vaults`

Read first: `docs/CODE_REVIEW_01.md` (the reasons for every item below), `docs/HANDOFF.md`, `docs/STATUS.md`, `docs/VAULT_FORMAT_V2.md`, `docs/BACKUP_AND_RECOVERY.md`, `server/README.md`.

Do the parts in order. Commit each part separately. **Report to the owner at the end** (and earlier only if something blocks).

## Rules
- Same rules as Task 07: Xiaomi only, no Google Play services / Firebase / ML Kit, minSdk 28.
- **Public repo:** no real domain, IP, disk names, subnet, token, pin, pairing code or password in code, tests, commits or docs. Placeholders only. Grep the diff before every commit.
- **Server change approved by the owner for Part A only** (pasting this task = approval). Deploy as before: copy the code to `/opt/mvault/mvault/`, restart `mvault.service`, smoke test. No other server changes.
- The release app's vault is a **test vault** (password given in chat, never written anywhere). You may use it for every test here, including key rotation, restore, and re-pairing. Revoke the test devices you no longer need at the end and list the ones that remain.
- Vault format stays PVAULT2 v2. Rotation only changes the DEK and slots inside the existing format.

---

## Part A: `GET /vaults` + restore without typing the Vault ID (review F2)

1. **Server** (`server/mvault/server.py`, `common.py`): `GET /api/v1/vaults` with the same auth as the other `/vaults` routes (`self._auth()`; nginx's `location /api/v1/` already requires the client cert, so no nginx change is needed). Answer: `{"vaults": [{"vault_id", "latest_rev", "latest_time", "size", "device"}]}`, sorted by `latest_time` newest first, all vaults (single owner, not scoped per device). Unit tests in `server/tests/`, `server/README.md` API list, `tools/mvault_smoke.py` covers it. Deploy + smoke test.
2. **App:** `BackupTarget.vaults()`. Restore start screen: after pairing, show the list ("Vault 1a2b…, revision 42, 3 Nov 2026, from xiaomi"). Tap one to see its revisions (existing flow). Keep **"Enter Vault ID manually"** as a fallback (and use it if the server answers 404, i.e. an old server).
3. **Settings → Backup:** show this vault's ID with a copy button.
4. **Tests:** server unit tests; app parsing + fallback on 404. Live: clear the **debug** app's data → Restore from backup → the list shows the vault → restore works.

## Part B: DEK rotation (review F1)

1. **core-crypto:** `UnlockedVault.rotateDek(password: CharArray, newRecoveryKey: Boolean, kdf: Argon2Params): RecoveryKey?`. It creates a new random DEK, wipes the old one, writes a new password slot (fresh salt), and, if `newRecoveryKey`, a new recovery slot (returns the key; otherwise the vault has no recovery slot). `seal()` afterwards encrypts the body with the new DEK. Update `docs/VAULT_FORMAT_V2.md` ("DEK rotation").
2. **Use it in:**
   - **New recovery key** (Settings button, "Create recovery key", and the yearly "make a new kit" card): ask for the **master password** first, then `rotateDek(pw, newRecoveryKey = true)` → Emergency Kit flow as today.
   - **Remove recovery key:** after the existing password check, `rotateDek(pw, newRecoveryKey = false)`.
   - **Change master password:** unchanged by default (DEK stays, so the recovery key still works). Add a checkbox **"Someone may know my old password"**. When it is ticked, call `rotateDek(newPw, newRecoveryKey = vault had one)` and show the new Emergency Kit. Explain this in one line under the checkbox.
   - Recovery-key unlock → forced new password: no rotation needed (the kit was used, not lost). Keep as is.
3. **After any rotation:** turn off fingerprint unlock (`disableBiometric`) and show "Fingerprint unlock was turned off because the vault key changed. Turn it on again in Settings → Security." Clear `kitPending` correctly.
4. **Honest texts:** the confirm dialogs and success messages for a new or removed key should say that old backups and local snapshots still open with the old key or password, but **newer versions don't**. Version history password fallback: "Enter the master password this version was saved with."
5. **Test restore** after a rotation: if the server's latest rev is lower than the local rev, show "The newest version is still uploading. Try again in a minute." instead of a decrypt failure.
6. **Tests:** core-crypto tests showing that after rotation the old password and old recovery key **don't** open the new file, the new ones do, the old file still opens with the old credentials, and the body DEK actually changed (old DEK fails on the new file). App tests for the three flows. Live on the Xiaomi (release app, test vault): new recovery key → backup uploads → test restore OK → version history of an older rev asks for the password → works.

## Part C: small fixes (review F3–F7, L2)

1. **F3 TLS retry:** in `BackupErrors.fromIo`, pin mismatch stays `PIN_MISMATCH` (stopped). Other `SSLHandshakeException`/`SSLPeerUnverifiedException` become retryable. Count consecutive TLS failures in `BackupStatus`; from 3 on, show the red "Backup error" chip with the message. Unit test.
2. **F4:** `createVault` refuses if `repo.exists()` (same text style as restore).
3. **F5:** `testRestore` returns early when the vault is locked (no zero key).
4. **F6:** pairing payload without pins: refused in **release** builds with a clear message; the confirm dialog stays in debug builds only.
5. **F7 certificate expiry:** read `notAfter` from `backup/client.pem`. Settings → Backup: "Certificate valid until <date>". From 30 days before expiry: amber chip "Reconnect to the backup server before <date>". Unit test for the threshold. `mvault-admin list` on the server: also print each device's cert expiry, if that's easy (it's a server change, but allowed as part of this task).
6. **L2:** `BiometricVault` key gets `setUnlockedDeviceRequired(true)`.

## Part D: finish

1. All unit tests, UI tests on the Xiaomi, lint 0 errors, release build OK, permissions unchanged.
2. Install the release build on the Xiaomi, keeping its data (same signing key). Backup chip green, test restore OK.
3. Update `docs/STATUS.md`, `docs/HANDOFF.md`, `docs/DECISIONS.md` (DEK rotation, `GET /vaults`, no pinless pairing in release), and tick F1–F7/L2 in `docs/CODE_REVIEW_01.md`.
4. Report: what changed, test results, which devices remain on the server, and a **short manual checklist for the owner** (fingerprint re-enable after rotation; the open checks from Task 07 still apply).
