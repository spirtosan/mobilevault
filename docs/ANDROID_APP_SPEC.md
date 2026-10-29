# Mobile Vault (Android): app specification

## Tech stack
| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 (dark theme, palette taken from the desktop app) |
| Min / target SDK | minSdk 28 (Android 9), targetSdk = latest |
| Crypto | `javax.crypto` AES-256-GCM, PBKDF2WithHmacSHA256 (v1 import), BouncyCastle Argon2id |
| Key storage | Android Keystore (StrongBox if available), `androidx.biometric` BiometricPrompt (BIOMETRIC_STRONG) |
| Background | WorkManager (backups), DataStore (non-secret settings) |
| Network | **OkHttp** over HTTPS to the own server's append-only API; mTLS client cert in Android Keystore + bearer token + certificate pinning; no cleartext (`network_security_config`) |
| Serialization | kotlinx.serialization |
| Storage | one encrypted vault file in `filesDir` plus a snapshots folder (no database; everything is encrypted in a single blob, like the desktop app) |

## Feature parity with desktop v1.3
- [ ] Create vault / unlock with master password
- [ ] Entry list with search (Service, Username, URL) and sort
- [ ] Add / edit / delete entry (Service and Password required); delete asks for confirmation
- [ ] Show/hide password, copy username, password and URL, open URL
- [ ] Password strength meter (upgrade to zxcvbn-style scoring)
- [ ] Clipboard auto-clear (configurable, default 30 s)
- [ ] Auto-lock after inactivity (default 5 min) **and** on app background (configurable grace period)
- [ ] Change master password
- [ ] Atomic saves
- [ ] Import desktop `.vault` (PVAULT1); export PVAULT1 for desktop

## New for mobile
- [ ] Biometric unlock (fingerprint/face) as a convenience; the master password is still required after reboot, every 7 days, and after 5 failed biometric attempts
- [ ] Recovery key + Emergency Kit (see BACKUP_AND_RECOVERY.md)
- [ ] Master password reminder: hint + periodic confirmation (see below)
- [ ] Remote backups to own Ubuntu server via HTTPS backup API (see BACKUP_AND_RECOVERY.md); optional SAF/USB copy; version history; restore; test restore; backup health indicator
- [ ] Local snapshot history
- [ ] Password generator (length, character sets, passphrase mode)
- [ ] Notes field, favourites, tags
- [ ] (Phase 2) Android Autofill service, multi-device merge, TOTP codes

## Master password reminder
Forgetting the master password is the most likely way to lose everything, especially once biometric unlock means it's rarely typed. Three layers:
1. **Password hint** (optional, set when creating the vault or changing the password)
   - Shown on the unlock screen after the first failed attempt (or by tapping "Show hint").
   - Stored **unencrypted** in the vault header (needed before unlock), so the hint is also visible to anyone who gets the backup file. The UI says so clearly.
   - Rejected if it contains the password, or any 4+ character piece of it (case-insensitive), or if it's longer than 100 characters.
2. **Periodic confirmation**: after biometric unlock, ask for the master password instead if it hasn't been typed for N days (default 7, choices 3/7/14/30). Plus a dismissible "Practice your master password" card every 30 days: the password is checked by re-deriving the key, and nothing changes.
   Wrong answer: the card offers to show the hint, or to start recovery with the recovery key while biometrics still work, so the user can set a new password **before** it's actually lost.
3. **Recovery key / Emergency Kit**: the last resort when the password is fully forgotten (see VAULT_FORMAT_V2.md). The app reminds the user yearly to check the kit is still stored safely.

## Mobile-specific hardening
- `FLAG_SECURE` on all windows: no screenshots, hidden in the recent-apps preview.
- `android:allowBackup="false"` plus `dataExtractionRules` that exclude everything. Our own encrypted backups replace Google auto-backup, and Keystore keys can't be restored anyway.
- Clipboard: set `ClipDescription.EXTRA_IS_SENSITIVE` (Android 13+); clear it after the timeout via `clearPrimaryClip()`.
- Password fields: `IME_FLAG_NO_PERSONALIZED_LEARNING`, `KeyboardType.Password`, autocorrect off.
- Keep secrets in `CharArray`/`ByteArray` and zero them on lock (best effort on the JVM).
- Lock immediately on screen-off; wipe the in-memory DEK and decrypted entries.
- Brute-force protection: after 5 failed unlocks, add a growing delay. Optional: wipe after N attempts, **only if** a verified remote backup exists.
- Warn if the device is rooted or has an unlocked bootloader (Play Integrity API) or has no screen lock.
- The INTERNET permission is used only by the backup module; no analytics, no telemetry.
- Release build: R8 minification, `debuggable=false`, signed with a key kept offline.

## Screens
1. Onboarding: Create new vault / Import desktop file / Restore from backup
2. Create vault: master password + strength meter, then the recovery key / Emergency Kit, then set up a backup target (strongly recommended, can be skipped with a warning)
3. Unlock: password field, biometric button
4. Vault list: search bar, entries, FAB "+", backup status chip
5. Entry detail / edit (same fields as desktop + notes), generator button
6. Settings: security (auto-lock, clipboard, biometrics, change password, regenerate recovery key), backups (targets, history, restore, test restore), import/export, about

## Suggested module layout
```
app/                 Compose UI, navigation, ViewModels
core-crypto/         PVAULT1 reader, PVAULT2 reader/writer, Argon2, key slots  (pure Kotlin, unit-tested)
core-vault/          repository, atomic save, snapshots, merge
backup/              BackupTarget interface + HTTPS API (OkHttp) / SAF implementations, WorkManager jobs
```

## Milestones
1. **M1 – core-crypto**: PVAULT1 decrypt passes `test-vectors/`; PVAULT2 round-trip; recovery slot.
2. **M2 – MVP app**: create/unlock/list/edit, auto-lock, clipboard, import from desktop.
3. **M3 – Backups**: snapshots, own-server backup + server-side archive, restore, health indicator, Emergency Kit, password hint/reminders.
4. **M4 – Polish**: biometrics + periodic password confirmation, generator, test restore, export to desktop; optional SAF/USB target.
5. **M5 – Phase 2**: Autofill, multi-device merge, desktop app PVAULT2 support.

## Decisions
See `DECISIONS.md`. In short: GPL-3.0; backups to own Ubuntu server only (no Google Drive); master password reminder; app ID `com.spirtosan.mobilevault`.
