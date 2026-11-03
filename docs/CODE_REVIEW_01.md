# Code review 01: security review of M2 + Task 06 + Task 07

Date: 2026-11-03. Reviewer: Claude (cloud session), reading the local repo at 436a11f.
Scope: `android/core-crypto`, `android/app` (backup, security, data, ViewModel flows, manifest, network config), plus the server auth path (`server/mvault/server.py`, `nginx-vault.conf`). UI layout and the tests themselves were not reviewed.

**Summary:** the code is in good shape. The crypto core, Keystore use, TLS/mTLS setup and the append-only upload logic are done correctly. There is **one real design gap**: the vault key (DEK) never changes, so it isn't rotated after a lost Emergency Kit. There are also **one availability gap** (restore needs the Vault ID) and a handful of small fixes. Nothing is urgent enough to stop using the app.

Priorities: **Fix now** = goes into Task 08. **Later** = noted, no action yet.

**Status 2026-11-03:** F1–F7 and L2 are done in Task 08 (✅ below; commits 4e50ba2, 1a37c70, 8b9dbcc, 92843d0).

---

## Fix now

### ✅ F1. The vault key (DEK) never rotates, so the old kit or old password keeps working: medium
`UnlockedVault.changePassword`, `regenerateRecoveryKey` and `removeRecoveryKey` only re-wrap the **same** DEK. Every older copy of the vault file still has the old slots:
- server revisions (append-only, kept by retention),
- local `snapshots/` (20 newest + one per day for 30 days),
- desktop exports.

So someone who has the **old Emergency Kit** (or old password) and **any old copy** of the file can unwrap the DEK. Because the DEK is the same, it also decrypts **every future revision** they can get. The UI says "New recovery key saved. The old one no longer works." That is only true for the newest file.

The case where this matters most is "I lost my printed kit", which is exactly why someone regenerates the key.

**Fix:** add `UnlockedVault.rotateDek(...)`: a new random DEK, the body re-sealed, and new slots created.
- **New recovery key** (Settings and the yearly reminder "make a new kit"): ask for the master password first (needed to re-wrap the password slot), then rotate.
- **Remove recovery key:** the password is already checked, so rotate.
- **Change master password:** keep it as it is by default (the DEK stays, so the recovery key still works). Add a checkbox, "Someone may know my old password". When it is ticked, rotate the DEK. A vault with a recovery key then also gets a new recovery key and Emergency Kit, because the old recovery slot can't be re-wrapped without the old key.
- After a rotation: fingerprint unlock is turned off, with the message "turn it on again". It can't be re-wrapped without a prompt.
- Version history: older revisions no longer open with the current DEK. The existing password fallback then applies, and its text should say *"enter the master password this version was saved with"*.
- Test restore right after a rotation: if the server's latest rev is lower than the local rev, say "still uploading" instead of "could not be decrypted".

Old files still open with the old credentials. That can't be avoided (append-only), and they contain only old data. Fix the success messages so they are honest about this.

### ✅ F2. Restore needs the Vault ID, which may exist only on the lost phone: medium (availability)
The server has no "list vaults" call, so a new phone must type the Vault ID. The ID is printed on the Emergency Kit, but **a vault created without a recovery key has no kit**. Then the ID exists only on the phone that was lost. Recovery would need SSH to the server and reading directory names.

**Fix:** `GET /api/v1/vaults` (cert + token). It returns one row per vault: `vault_id`, latest rev, time and size, and the device of the latest rev. Vaults aren't scoped per device today (single owner), so listing all of them is consistent. The restore screen shows the list (newest first) and keeps "Enter Vault ID manually" as a fallback. Also show the Vault ID in Settings → Backup with a copy button, if it isn't there already.

### ✅ F3. TLS handshake errors stop the upload chain: low
`BackupErrors.fromIo` maps every `SSLHandshakeException` / `SSLPeerUnverifiedException` to `TLS` (not retryable). A dropped connection during the handshake, or a captive portal, then sets `stopped`, and nothing retries until the app is opened again. Nothing is sent before the handshake completes, so retrying is safe.

**Fix:** keep `PIN_MISMATCH` as stopped. Make other TLS failures retryable (WorkManager backoff). After 3 consecutive TLS failures, show the red "Backup error" chip.

### ✅ F4. `createVault` has no "a vault already exists" guard: low
`restoreUnlock` refuses when `repo.exists()`, but `createVault` doesn't. Today it is only reachable from Onboarding, so it's safe, but one future navigation bug would replace the real vault with a new one. The old vault would survive only in snapshots for 30 days and on the server.

**Fix:** the same one-line guard as in restore.

### ✅ F5. Test restore with a locked vault uses a zero key: low
`testRestore` passes `?: ByteArray(32)` when the vault is locked. The user then sees "could not be decrypted", a false alarm. **Fix:** return early if locked.

### ✅ F6. Pairing without pins is still possible after a warning: low
`mvault-admin pair` always sends pins, because `backup_pins` is configured on the server. A pinless QR therefore means something is wrong. **Fix:** refuse pairing without pins in **release** builds (keep the confirm dialog in debug).

### ✅ F7. Client certificate expiry is invisible: low (availability)
Client certs are valid for 825 days (`client_cert_days`). The `xiaomi` pairing expires around **February 2029**. After that nginx answers 403, and the app shows "Disconnected": backups stop and only the red chip says so.

**Fix:** read `notAfter` from `backup/client.pem`. Show "Certificate valid until …" in Settings → Backup. From 30 days before expiry, show an amber chip: "Reconnect to the backup server before <date>". Re-pairing already works.

---

## Later (no action now)

- **L1 Clipboard after the process is killed:** the 30 s clear runs in the app's coroutine scope. If HyperOS kills the process before then, the copied password stays on the clipboard until something else replaces it (it is marked sensitive, so it's hidden from previews and keyboards). Possible fix: also schedule a one-shot WorkManager job that clears it. Small risk; leave it.
- ✅ **L2 Biometric key hardening:** add `setUnlockedDeviceRequired(true)` to the fingerprint key. Do it when Task 08 touches `BiometricVault` anyway (one line, no behaviour change in normal use).
- **L3 Pairing crash window:** `BackupManager.pair` writes the new `token.bin` before the new `config.json`. A crash exactly between the two leaves the old config with the new token, so the phone shows "Disconnected" and re-pairing fixes it. Negligible.
- **L4 Release signing key:** if the release keystore is lost, the app can't be updated, and reinstalling **deletes the local vault**. The server backup still saves the data, but keep the keystore in `mobilevault-secrets` **and** on the offline USB stick (owner check).
- **L5 Server access is not per device:** any paired, non-revoked device can read every vault's encrypted revisions. That's fine for one owner. If the phone is lost, run `mvault-admin revoke <name>`. That step is already in the docs.
- **L6 Dependencies:** OkHttp 4.12, `androidx.biometric` 1.1.0, ZXing embedded 4.3.0 are older but have no known issues relevant here. Cover them in the planned dependency upgrade pass.

---

## Checked, no problems found

- **PVAULT2 crypto:** AES-256-GCM with random 96-bit nonces; header as AAD; slot AAD binds type and vault id; Argon2id matches RFC 9106 (test vector in `Argon2Test`); a KDF floor of 32 MiB / t=2 on read protects against downgraded headers; the recovery key is 160 bits with HKDF and a checksum; NFC-normalised password; keys and plaintext buffers are wiped on a best-effort basis; constant-time compare in `verifyPassword`.
- **Atomic saves:** tmp + fsync + atomic rename for the vault, snapshots and backup state files; a snapshot is taken before every save; only PVAULT2 bytes reach the disk.
- **Keystore:** the client key is EC P-256, non-exportable, StrongBox with TEE fallback, and the issued cert is checked against our own public key before install; the token is encrypted with a separate Keystore AES-GCM key; the fingerprint key needs a strong biometric per use, is invalidated on new enrolment, and its policy (reboot, N days, failures) is enforced before the prompt.
- **Network:** cleartext off; system CAs only (no user CAs); TLS 1.2/1.3; SPKI pins from the QR, with a spare-key pin on the server and `--reuse-key` on certbot, so Let's Encrypt renewals don't break pinning; no redirects; timeouts; nothing logged.
- **Server auth:** nginx requires `ssl_verify_client` = SUCCESS (depth 1, own CA) for `/vaults`; the app compares cert fingerprint + token hash with `hmac.compare_digest`; revoked → 403; the backend listens on localhost only; sha256 and header vault_id/rev are checked on PUT; 409 never overwrites; pairing codes are single use, time-limited and rate-limited.
- **Upload / 409 logic:** uploads are verified by listing; a 409 with the same sha256 counts as success; a different sha256 → re-seal at `max(local, remote) + 1` on unlock; rollback is saved as a new rev; restore checks vault_id and rev in the downloaded header before saving.
- **Android hardening:** `allowBackup=false` + data-extraction rules exclude everything; FLAG_SECURE (debug-only override that R8 removes); no foreground service; lock on screen off, background (grace) and idle; the unlock throttle survives restarts; the clipboard is marked sensitive and cleared on lock.
