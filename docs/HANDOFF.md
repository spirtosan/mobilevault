# Handoff for the next session

_Last updated: 2026-10-30. Read this first, then `docs/STATUS.md`._

## 1. What this project is
**Mobile Vault** is an Android password manager for one owner (non-commercial, GPL-3.0). It is based on the desktop app **Shu PassVault** (`C:\Users\spirt\passvault`, Python/Tkinter; copy in `reference/desktop-passvault/`).
The main goal is **maximum protection against losing passwords**: strong offline encryption, a recovery key for a forgotten master password, password reminders, and versioned zero-knowledge backups to the owner's **own Ubuntu server**.

## 2. Where everything is
| Thing | Location |
|---|---|
| Repo (public) | `github.com/spirtosan/mobilevault`, branch `main`. Local clone: `C:\Users\spirt\mobilevault` |
| Specs | `docs/ANDROID_APP_SPEC.md`, `docs/VAULT_FORMAT_V2.md`, `docs/BACKUP_AND_RECOVERY.md`, `docs/DECISIONS.md` |
| Server state + hardening log | `docs/SERVER_CHECK.md` |
| Progress + to-dos | `docs/STATUS.md` |
| Encryption library (M1, done) | `android/core-crypto` (Kotlin/JVM). Tests: `cd android && gradlew.bat :core-crypto:test` (27/27) |
| Python reference of PVAULT2 | `android/tools/pvault2.py` |
| Backup server code | `server/` (Python stdlib). Installed on the server in `/opt/mvault`, config `/etc/mvault/config.json`, admin CLI `mvault-admin` |
| Backup API | `https://vault.<DOMAIN>/api/v1` (live; smoke test `server/tools/mvault_smoke.py`) |
| Secrets (never in git) | `C:\Users\spirt\mobilevault-secrets\` (TLS spare key `vault-spare.key`, tracker archive) |

## 3. How we work (important)
- **Cloud Claude session** (claude.ai, project "Mobile Vault"): designs and writes code/docs straight into `C:\Users\spirt\mobilevault` through the linked-computer tools (`device_bash`, `device_commit_files`). It **cannot** reach GitHub, Maven/Google repos or the server. It can compile and test Kotlin with the Kotlin 2.0.21 compiler in `/opt/gradle-8.14.3/lib` plus a JUnit stand-in, and run Python tests.
- **Local Claude Code** (on the owner's PC): has SSH to the server and push access to GitHub. It builds with Gradle and deploys. It receives work as `docs/CLAUDE_CODE_TASK_NN.md` files plus a short prompt the owner pastes. **Tasks 01–04 are done; 05 (M2) has been handed over; the next new one is 06.**
- The owner relaying a task **is** the approval for the changes listed in it. Server changes always use the safeguards: back up files first, run `nginx -t` / `sshd -t` before reloading, keep a session open, and use auto-rollback timers for firewall/SSH changes.
- The repo is **public**: use **placeholders only** (`<DOMAIN>`, `<DATA_DISK>`, `<MIRROR_DISK>`, `<LAN_SUBNET>`, `<ADMIN_USER>`). No IPs, hostnames, usernames, passwords or keys in commits. No passwords in chat either.
- The owner prefers plain explanations, a recommendation, and a ready-to-paste prompt for Claude Code.

## 4. Key decisions (details in DECISIONS.md)
- Kotlin + Jetpack Compose, minSdk 28, app ID `com.spirtosan.mobilevault`, GPL-3.0.
- Vault format **PVAULT2**: random DEK; slots = password (Argon2id 64 MiB/t3/p1, NFC-normalised) + recovery key (160-bit, 36 chars Crockford Base32 + checksum, HKDF); header = GCM AAD; optional plaintext hint; `rev` counter; entry UUIDs + tombstones. Reads desktop **PVAULT1** for import.
- Backups: **only the own server**, over HTTPS 443 through the existing nginx. Append-only API (no delete/overwrite); mTLS client cert (key in Android Keystore) + bearer token; QR pairing (`mvault-admin pair <name>`, payload has URL, one-time code and SPKI pins incl. the backup pin); `revoke` for lost phones. Retention: last 30 + weekly for 52 weeks; hourly root-owned mirror to the 2nd disk.
- No Google Drive, no SFTP, SSH is LAN-only (not port-forwarded).

## 5. Next steps
### Test phones (answered 2026-10-30)
Huawei Android 10 (API 29, **probably no Google services**, so no GMS/ML Kit/Firebase anywhere; use ZXing for QR), Motorola Android 12 (API 31), Xiaomi Android 15 (API 35, edge-to-edge, aggressive autostart limits). Android Studio is installed on the PC (JDK 21 in its `jbr`, not on PATH).

### M2 has been handed to Claude Code as `docs/CLAUDE_CODE_TASK_05.md`
Claude Code builds the app (it has the Android SDK and the phones); the cloud session reviews. Step 1 of the task is an Argon2 benchmark on all three phones; its results set the KDF parameters.

### M2: Android app MVP (Task 05, scope summary)
1. Create the `app` module (AGP, Compose, Material 3 dark theme with the desktop palette, minSdk 28) and `include(":app")`; depend on `:core-crypto`.
2. Screens: onboarding (create / import desktop file / restore later), create vault (password + strength + optional hint via `HintPolicy`, Emergency Kit showing the recovery key once with a 3-random-character confirmation), unlock (hint after the first failure), list + search, entry add/edit/delete, settings.
3. Storage: vault file in `filesDir`, atomic write (tmp + fsync + rename), local snapshots (last 20 + daily for 30 days).
4. Security: `FLAG_SECURE`, lock on background/screen-off + 5 min idle, clipboard clear + `EXTRA_IS_SENSITIVE`, no-learning IME flags, `allowBackup=false`, no INTERNET permission yet.
5. **Tune Argon2 on the real phone** (PC: 307 ms for create+unlock; target 0.5–1 s per unlock, never below 32 MiB/t2).
6. Desktop import via SAF file picker → `Pvault1.decrypt` → PVAULT2.

### M3: backup client + reminders
Pairing (scan QR → Keystore EC P-256 key → CSR → `POST /pair`), OkHttp with mTLS + pins, WorkManager upload queue, version list + restore + test restore, backup health indicator, biometric unlock (Keystore-wrapped DEK), periodic password confirmation, yearly Emergency Kit reminder.

### Later
Autofill service, multi-device merge, adding PVAULT2 to the desktop app (start from `android/tools/pvault2.py`).

## 6. Open to-dos outside the app
Owner: copy `vault-spare.key` to an offline USB stick; check that the old tracker seed passwords (given in chat on 2026-10-30) aren't reused anywhere; test `sudo -v` as the admin user.
Server (future task): stale TURN `external-ip`; restic + off-site backup of the server itself; 3 apt updates; remove dead nginx `/fshu/` and `/fshu3/`; optionally `PermitRootLogin no`.

## 7. Lessons learned (avoid repeating)
- Ubuntu 24.04 ships **nginx 1.24**: there's no `http2 on;`, and `listen … http2` affects every vhost on :443, so leave http2 off the vault vhost.
- fail2ban strips the timestamp before matching, so don't put patterns inside `[...]`. On 24.04 the sshd jail needs `journalmatch = _SYSTEMD_UNIT=ssh.service + _COMM=sshd`.
- `rsync -a` keeps the owner; use `--chown=root:root` for a root-owned mirror.
- Python `unittest` discovery needs `tests/__init__.py`.
- Kotlin: inside `map.apply { }`, the name `entries` means the **map's** entries, so qualify it with `this@Class.entries`.
- sshd uses the first value it reads: put overrides in `sshd_config.d/00-*.conf` (cloud-init's `50-` file sets PasswordAuthentication).
- The cloud sandbox refuses `rm -rf` with globs after `cd`; use fresh `mktemp -d` dirs. Transfer files to the PC as a `.tgz` via `device_commit_files`, then extract (`*.tgz` is git-ignored; the linked shell can't delete files).
- Don't let the nginx catch-all serve app folders: delete folders **before** removing their deny rules.
