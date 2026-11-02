# Task 07 for Claude Code: M3, server backups + restore, biometric unlock, password reminders

Owner decision 2026-11-03: **test on the Xiaomi only** (Motorola and Huawei skipped for now). Still keep the Huawei rules: **no Google Play services / Firebase / ML Kit**, minSdk 28.
Read first: `docs/HANDOFF.md`, `docs/STATUS.md`, `docs/BACKUP_AND_RECOVERY.md` (API + design), `docs/ANDROID_APP_SPEC.md` (reminders, biometrics), `server/README.md`, `server/mvault/server.py` (exact `/pair` and PUT behaviour), `server/mvault/admin.py` (`pair` QR payload, `spki_pin` format).

Do the parts in order. Commit each part separately. **Report to the owner after Part A** (pairing works against the live server) and at the end.

## Rules
- The `INTERNET` permission is added now, used **only** by the backup module. No analytics, no other network calls. `usesCleartextTraffic=false`, network security config: no user CAs, TLS 1.2+.
- Public repo: no real domain, IP, token, pin, code or password in code, tests, commits or docs. The server URL and pins come **only** from the pairing QR at runtime.
- Uploads never need the vault to be unlocked (the file is already encrypted). Never upload or log plaintext, tokens or keys.
- Server work: you may SSH to the server to run `mvault-admin pair/list/revoke/stats` and read logs. Don't change the server code or config without asking the owner. Test uploads stay on the server (append-only); that's fine, they're encrypted, retention will prune them.
- The vault in the owner's **release** app (the owner gives the password in chat; never write it to a file or commit) is a **test vault** (owner, 2026-11-03). You may use it for any test, including pairing the release app (e.g. as `xiaomi`), restore, rollback and revoke, without asking. The debug app (`com.spirtosan.mobilevault.debug`) can still be used and paired as its own device (e.g. `xiaomi-debug`). Revoke test devices you no longer need at the end and list the remaining ones.
---

## Part A: pairing + secure connection

1. **Dependencies:** OkHttp (current stable), WorkManager, `androidx.biometric` (for Part D), ZXing for QR (`zxing-android-embedded` or ZXing core + CameraX; no Google code). `CAMERA` permission only for scanning, requested at that moment.
2. **Device key:** EC P-256 key pair in Android Keystore (alias e.g. `mvault-client`), StrongBox if available, non-exportable. Build a PKCS#10 CSR signed with that key (BouncyCastle PKIX or hand-built DER, your choice; check the server accepts it: `openssl req -verify`, EC prime256v1).
3. **Pairing screen** (Settings → Backup → "Connect to backup server", and from Onboarding/Create, see Part C):
   - "Scan QR code" (camera) **and** "Paste pairing text" (the JSON payload `mvault-admin pair` prints). The paste option is how you test via adb, and a fallback if the camera fails.
   - Validate the payload (`t == "mvault-pair"`, `v == 1`, https URL, code, pins). If no pins: refuse unless the owner explicitly confirms (warning dialog).
   - `POST {url}/pair` with `{code, csr_pem}` → store `cert_pem`, `ca_pem`, `token`, url, pins, device name. Put the issued cert chain on the Keystore key entry (so the private key never leaves Keystore). Token: encrypt with a separate Keystore AES key, store in app-private files. Nothing in DataStore in plaintext except url/device name.
   - Errors in plain words: expired/used code ("Run `mvault-admin pair` again"), pin mismatch ("This is not your server, or its certificate changed"), no network.
4. **Client:** OkHttp with a `KeyManager` from AndroidKeyStore (client cert), `CertificatePinner` from the QR pins, TLS 1.2+, sensible timeouts, `Authorization: Bearer`. Implement `BackupTarget` (`upload`, `list`, `download`, `latest`, `health`) as in BACKUP_AND_RECOVERY.md. No delete.
5. **Settings → Backup:** server name/device, "Test connection" (`/health` + `/latest`), "Disconnect this phone" (deletes local key/token/cert; text tells the owner to also run `mvault-admin revoke <name>` on the server).
6. **Test:** unit tests for payload parsing, pin handling, CSR (parse it back), error mapping. Then live: SSH → `mvault-admin pair xiaomi-debug` → paste the payload via adb into the debug app → Test connection OK. Also check: wrong pin → refused; reused code → clear error; `mvault-admin revoke` → app shows "This phone was disconnected from the server".

**Report to the owner here**, then continue.

## Part B: automatic uploads + health

1. After **every save** (and on app start/unlock) enqueue a unique WorkManager job (`KEEP`/`APPEND_OR_REPLACE` so only one runs), network constraint, exponential backoff. It reads `vault.pvault` from disk, `PUT /vaults/{id}/revs/{rev}` with `X-Content-SHA256`.
2. Results:
   - 201 → verify: `GET …/latest` (or the revs list) has the same rev + sha256 → record "last successful backup" (time, rev).
   - 409 → if the server's sha256 for that rev equals ours, treat as success. If it differs (e.g. after a restore of an older version, or another device), mark **"needs new revision"**: at the next unlock, re-seal the vault with `rev = max(local, remote latest) + 1` and upload. Never overwrite.
   - 401/403 → stop retrying, show "disconnected" state. 413/422 → error state with the server message; don't retry forever.
   - Network errors → retry (WorkManager backoff).
3. Snapshots of every rev stay local as now; nothing on the server is ever deleted by the app.
4. **Health indicator:** a chip on the vault list ("Backed up 2 h ago" green / "Not backed up for 3 days" amber / "7+ days or error" red / "No backup set up" red, tap → Settings → Backup). Also "Changes not backed up yet" when local rev > last uploaded rev. Optional setting: "Only on Wi-Fi" (unmetered constraint), default off.
5. Battery: no foreground service; uploads also retried on app open, because HyperOS may delay/kill background work. Tell the owner if he should allow autostart / no battery restriction for the app.
6. Tests: worker logic with a fake `BackupTarget` (201, 409 same/different sha, 401, network error), health thresholds. Live: edit an entry in the debug app → backup within a minute → `mvault-admin stats`/`list` shows it. Airplane mode → queued → off → uploaded.

## Part C: restore + test restore

1. **Onboarding → "Restore from backup"** (replace "coming soon"): pair (Part A) → list revisions (date, rev, size; newest first) → download → check header `vault_id` → unlock with **master password or recovery key** (if the file has one) → save as the local vault (atomic) → upload continues from that rev.
2. **Settings → Backup → Version history** (vault unlocked): list revisions → pick one → preview (entry count, date; decrypt in memory with the current DEK, or password if the DEK differs) → "Restore this version" → snapshot the current vault first, then save the old content as a **new** rev (`max + 1`), so nothing is lost and the server gets it as a new revision.
3. **Test restore:** Settings button + reminder card every 30 days: download latest, decrypt in memory (`verifyWithDek`), compare entry count with local, show ✓ with date. Never writes anything.
4. **Create flow:** after the recovery-key step, a "Back up to your server" step: "Connect now (recommended)" or "Skip" (then the red "No backup set up" chip stays).
5. Tests: restore picks the right file, rev bump on rollback, 409 after rollback handled, wrong vault id refused. Live in the debug app: rollback to an older rev, test restore, and a full **"new phone"** run: clear the debug app's data → Restore from backup (pair again as `xiaomi-debug-2`, revoke the old one) → unlock → entries match.

## Part D: biometric unlock

1. Settings → Security → "Unlock with fingerprint" (only if `BIOMETRIC_STRONG` is available; otherwise say why it's unavailable). Turning it on needs the master password.
2. Keystore AES-GCM key: `setUserAuthenticationRequired(true)` with auth per use (BiometricPrompt + `CryptoObject`), `setInvalidatedByBiometricEnrollment(true)`. Wrap the DEK (`exportDekForBiometric()`), store the wrapped blob in app-private files; open with `Pvault2.unlockWithDek`.
3. Master password still required: after reboot (compare `Settings.Global.BOOT_COUNT`), when the password wasn't typed for N days (default 7; choices 3/7/14/30), after 5 failed biometric attempts, and if the key was invalidated (new fingerprint enrolled → "Fingerprints changed, enter your master password", then re-enable).
4. Unlock screen: fingerprint prompt opens automatically when allowed; password field always available.
5. Disabling biometrics, changing the master password (DEK unchanged, keep it working), restore/new vault (DEK changed → disable and wipe the blob) all handled.
6. Tests: policy logic (reboot, N days, failures, invalidation) as unit tests. On the Xiaomi: the owner has to touch the sensor, so **ask him** for a short manual check list at the end (adb can't do fingerprints; use `adb emu`-style tricks only if they exist on a real phone, they don't).

## Part E: password reminders

As in ANDROID_APP_SPEC.md "Master password reminder":
1. The N-day rule from Part D (password instead of fingerprint).
2. "Practice your master password" card every 30 days (dismissible): type it, check by re-deriving (`verifyPassword`), nothing changes. Wrong → offer "Show hint" and "Set a new password now" (while the vault is open, so it isn't lost).
3. Yearly card "Is your Emergency Kit still safe?" if the vault has a recovery key (options: "Yes", "Make a new key"). If it has none: the existing permanent warning stays; no nagging.
4. Debug-only setting to fast-forward the reminder clocks for testing.

---

## Done when
- All core-crypto + app unit tests and UI tests pass on the Xiaomi; lint 0 errors; release build passes. Merged manifest: `INTERNET` + `CAMERA` (+ biometric), nothing else new. Check the APK has no `com.google.android.gms` / Firebase classes.
- Live server checks done (Parts A–C), the release app paired and its backups verified on the server.
- Short owner checklist for what needs him: QR scan with the camera, fingerprint on/off/unlock, new-fingerprint invalidation, HyperOS battery/autostart setting.
- Docs updated: STATUS.md, HANDOFF.md, android/README.md, DECISIONS.md (OkHttp/ZXing/biometric choices), ANDROID_APP_SPEC.md checkboxes. Commit + push per part.
