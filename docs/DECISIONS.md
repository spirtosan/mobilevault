# Decisions log

| Date | Decision | Notes |
|---|---|---|
| 2026-10-29 | **Licence: GPL-3.0** | Same as the desktop app; non-commercial, open source. Lets code move freely between desktop and mobile. Any fork must stay open too. |
| 2026-10-29 | **Backup/sync target: own Ubuntu server only** | Google Drive dropped. Protocol (SFTP or WebDAV over HTTPS) to be chosen after the server check (`docs/SERVER_CHECK.md`). Server keeps an append-only archive the phone cannot delete. |
| 2026-10-29 | **Master password reminder** | (a) optional hint shown on the unlock screen, (b) periodic "confirm your master password" prompts when biometrics are used, (c) recovery key as the final fallback. See ANDROID_APP_SPEC.md. |
| 2026-10-29 | App ID `com.spirtosan.mobilevault` (proposed) | Change before the first release if desired. |
| 2026-10-29 | GitHub repo `spirtosan/mobilevault` | |
| 2026-10-29 | ~~Protocol: SFTP~~ → **HTTPS via existing nginx** | Owner: 443 is already public with a valid cert, so use HTTPS. Small append-only Python API behind nginx on subdomain `vault.<DOMAIN>`, mTLS client cert + bearer token per device, no DELETE. SSH stays LAN-only. Design in BACKUP_AND_RECOVERY.md. |
| 2026-10-29 | **Server hardening before any backup setup** | Key-only SSH, firewall, fail2ban (Task 02). Owner states SSH is currently NOT port-forwarded from the internet. |
| 2026-10-29 | **Access from outside home: via HTTPS 443** | Resolved by the decision above. Public IP per owner: <PUBLIC_IP> (matches DNS). |
| 2026-10-29 | **Backup server live** (HTTPS append-only API, mTLS + token) | Deployed via Task 03; smoke test 9/9. |
| 2026-10-29 | **Recovery key = 160-bit, Crockford Base32 + checksum, HKDF** (not 24 words) | No word list to ship; typo detection; QR-friendly. |
| 2026-10-29 | **Passwords NFC-normalised in PVAULT2** | Same key from different keyboards/OSes. PVAULT1 keeps raw UTF-8 (desktop compatibility). |
| 2026-10-29 | **core-crypto is a pure Kotlin/JVM module** (BouncyCastle Argon2, kotlinx-serialization-json) | Testable without Android; reusable on desktop. |
| 2026-10-30 | **Test phones: Huawei Android 10, Motorola Android 12, Xiaomi Android 16** (task said 15; the phone reports API 36) | minSdk 28 covers all. **No Google Play services dependencies** (the Huawei probably has none): QR via ZXing, no Firebase/ML Kit. Background uploads must cope with vendor battery killers (also upload on app open). |
| 2026-10-30 | **Argon2 parameters calibrated per phone at vault creation** | Strongest of 64/32 MiB × t3/t2 (p=4) with unlock ≤ 1 s, never below 32 MiB/t2; must still open on the slowest phone within ~3 s. Benchmark table below. |
| 2026-10-30 | **M2 implemented by local Claude Code** (Task 05) | It has the Android SDK and the phones for the build/test loop; the cloud session reviews. |
| 2026-10-30 | **SSH password login re-enabled for all users, including root** | Owner's choice (PuTTY with a password). Reverses the key-only part of the 2026-10-29 hardening. Acceptable only because SSH is not port-forwarded and ufw allows it from the LAN only. Details: SERVER_CHECK.md. |
| 2026-10-30 | **Own pure-Kotlin Argon2id with p=4 lanes** (replaces BouncyCastle's Argon2) | BouncyCastle was too slow on the phone. New `Argon2.kt`: one flat `LongArray`, no allocation in the inner loop, one thread per lane with sync at the 4 segment boundaries (RFC 9106). Byte-identical to RFC 9106 vector, BouncyCastle (p=1/2/4) and argon2-cffi. New vaults use p=4; old p=1 vaults open unchanged. BouncyCastle stays for PBKDF2 (desktop import) and as a test cross-check. Recovery slot stays HKDF (160-bit random key needs no stretching). |
| 2026-11-02 | **Recovery key is optional** (owner's choice) | Create flow offers "Create recovery key (recommended)" or "Skip" (with a warning that a forgotten password then loses everything). Settings can add, replace or remove it. Format: the `recovery` slot may be absent. |
| 2026-11-02 | **Master password minimum 6 characters** (was 8; owner's choice) | Under 6 is blocked. 6+ but shorter than 10 or below "Fair" shows an orange warning (offline guessing of a copied vault file or backup), Save stays enabled. Argon2 settings and the hint rule are unchanged. Export files for the desktop keep the 8-character minimum. |
| 2026-11-03 | **Test on the Xiaomi only for now** (owner) | Motorola and Huawei skipped; the Huawei rules (no Google Play services, minSdk 28) still apply. |
| 2026-11-03 | **OkHttp 4.12.0** for the backup client (Task 07) | OkHttp 5.x (5.5.1) needs compileSdk 37 and a newer Android Gradle plugin; 4.12 is the last 4.x and widely used on Android. Client: Android Keystore `KeyManager` (mTLS, key never leaves Keystore), `CertificatePinner` from the pairing QR, TLS 1.2/1.3 only, no redirects, system CAs only (network_security_config, no user CAs, no cleartext). |
| 2026-11-03 | **QR scanning: ZXing (`zxing-android-embedded` 4.3.0)**, plus "Paste pairing text" | No Google code (Huawei rule). The paste field is the fallback and how adb tests pair. Camera permission is requested only when the scanner opens. |
| 2026-11-03 | **CSR hand-built in DER** (no BouncyCastle in release) | PKCS#10 with the Keystore EC P-256 public key, signed SHA256withECDSA by the Keystore key; checked with `openssl req -verify` and by the live server. Key digests NONE (TLS) + SHA-256 (CSR); StrongBox when available (the Xiaomi has none, so TEE). |
| 2026-11-03 | **Uploads: WorkManager unique chain (APPEND_OR_REPLACE), no foreground service** | Queued after every save, on app open and on unlock; network constraint (optional "Only on Wi-Fi" = unmetered), exponential backoff. Each run uploads the current file and verifies rev + sha256 in the server's list. 409 with another sha256 → re-seal as `max(local, remote) + 1` at the next unlock (never overwrite). WorkManager adds the normal permissions WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED; its FOREGROUND_SERVICE permission and service are removed from the manifest. |
| 2026-11-03 | **Restore asks for the Vault ID** | The server API lists revisions per vault only (no "list vaults"), so a new phone needs the ID: from the Emergency Kit or `mvault-admin stats`. Possible later server change: a per-device "my vaults" endpoint, or the vault id in the pairing QR. |
| 2026-11-03 | **Rollback = old content as a new revision** | Version history restores entries only (password slots and recovery key stay as they are now), snapshots the current file first and saves as `max(local, remote) + 1`, so nothing is lost locally or on the server. |
| 2026-11-03 | **Fingerprint unlock: `androidx.biometric` 1.1.0 + Keystore AES-GCM key, auth per use, invalidated on new enrolment** | The key wraps the DEK; blob with the vault id in app-private files. Password required after reboot (`Settings.Global.BOOT_COUNT`), after N days (default 7), after 5 failures/lockout, and when the key is invalidated. New vault / restore (new DEK) wipes the blob; password change keeps it (DEK unchanged). MainActivity became a FragmentActivity (needed by BiometricPrompt). |
| 2026-11-03 | **Practice-password card counts from the last time the password was typed** | Without fingerprint unlock the password is typed at every unlock, so the 30-day card only appears when it's really rarely used. Emergency Kit check yearly, only with a recovery key. Debug builds can fast-forward the reminder clock. |

## Argon2 benchmark (Task 05 step 1)
Median ms per unlock, measured as (create + unlock) / 2 over 3 runs each, in the release-like `bench` build. No out-of-memory errors at 64 MiB.

| Phone | 64 MiB t3 p1 | 64 t2 p1 | 32 t3 p1 | 32 t2 p1 | **64 t3 p4** | 64 t2 p4 | 32 t3 p4 | 32 t2 p4 | Old BouncyCastle 64 t3 p1 | PBKDF2 600k (desktop import) | Chosen at creation |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Xiaomi (Android 16, API 36, 8 cores, 256 MiB heap) | 290 | 198 | 134 | 98 | **127** | 99 | 75 | 58 | 591 | 614 | 64 MiB/t3/p4 |
| Motorola (Android 12) | TBD | | | | | | | | | | |
| Huawei (Android 10) | TBD | | | | | | | | | | |

Notes: Xiaomi numbers are AOT-compiled; without AOT they are within ~5 %. The very first run on the Xiaomi (fresh install, BouncyCastle) gave 2546 ms for 64/t3 and 2032 ms for PBKDF2; a re-run under the same conditions as above gave 591 / 614 ms, so that first run was an artifact of the freshly installed app, not representative. PC (JVM): 157 ms for create+unlock at 64/t3/p4 (was 307 ms with BouncyCastle p1).
