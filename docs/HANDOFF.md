# Handoff for the next session

_Last updated: 2026-11-03. Read this first, then `docs/STATUS.md`._

## 1. What this project is
**Mobile Vault** is an Android password manager for one owner (non-commercial, GPL-3.0). It is based on the desktop app **Shu PassVault** (`C:\Users\spirt\passvault`, Python/Tkinter; copy in `reference/desktop-passvault/`).
The main goal is **maximum protection against losing passwords**: strong offline encryption, an (optional, recommended) recovery key for a forgotten master password, password reminders, and versioned zero-knowledge backups to the owner's **own Ubuntu server**.

## 2. Where everything is
| Thing | Location |
|---|---|
| Repo (public) | `github.com/spirtosan/mobilevault`, branch `main`. Local clone: `C:\Users\spirt\mobilevault` |
| Specs | `docs/ANDROID_APP_SPEC.md`, `docs/VAULT_FORMAT_V2.md`, `docs/BACKUP_AND_RECOVERY.md`, `docs/DECISIONS.md` |
| Server state + hardening log | `docs/SERVER_CHECK.md` |
| Progress + to-dos | `docs/STATUS.md` |
| Encryption library (M1, done) | `android/core-crypto` (Kotlin/JVM). Tests: `cd android && gradlew.bat :core-crypto:test` (42) |
| Android app (M2, Task 06, M3/Task 07, Task 08 done) | `android/app` (Compose). Build/test/install notes: `android/README.md`. Screenshots: `docs/screenshots/task06/` |
| Python reference of PVAULT2 | `android/tools/pvault2.py` |
| Backup server code | `server/` (Python stdlib). Installed on the server in `/opt/mvault`, config `/etc/mvault/config.json`, admin CLI `mvault-admin` |
| Backup API | `https://vault.<DOMAIN>/api/v1` (live; smoke test `server/tools/mvault_smoke.py`) |
| Secrets (never in git) | `C:\Users\spirt\mobilevault-secrets\` (TLS spare key `vault-spare.key`, tracker archive) |

## 3. How we work (important)
- **Cloud Claude session** (claude.ai, project "Mobile Vault"): designs and writes code/docs straight into `C:\Users\spirt\mobilevault` through the linked-computer tools (`device_bash`, `device_commit_files`). It **cannot** reach GitHub, Maven/Google repos or the server. It can compile and test Kotlin with the Kotlin 2.0.21 compiler in `/opt/gradle-8.14.3/lib` plus a JUnit stand-in, and run Python tests.
- **Local Claude Code** (on the owner's PC): has SSH to the server and push access to GitHub. It builds with Gradle and deploys. It receives work as `docs/CLAUDE_CODE_TASK_NN.md` files plus a short prompt the owner pastes. **Tasks 01–10 are done; the next new one is 11.**
- The owner relaying a task **is** the approval for the changes listed in it. Server changes always use the safeguards: back up files first, run `nginx -t` / `sshd -t` before reloading, keep a session open, and use auto-rollback timers for firewall/SSH changes.
- The repo is **public**: use **placeholders only** (`<DOMAIN>`, `<DATA_DISK>`, `<MIRROR_DISK>`, `<LAN_SUBNET>`, `<ADMIN_USER>`). No IPs, hostnames, usernames, passwords or keys in commits. No passwords in chat either.
- The owner prefers plain explanations, a recommendation, and a ready-to-paste prompt for Claude Code.

### How we test
- **Debug app only** (`com.spirtosan.mobilevault.debug`) with test data; never the release app (see the rule at the top).
- **Server tests clean up after themselves (Task 11):** the smoke test is run with `--cleanup ssh:<ADMIN_USER>@<SERVER_HOST>` (`python server/tools/mvault_smoke.py <URL> <CODE> --cleanup ssh:…`); at the end it runs `mvault-admin delete-vault --yes <test vault>` and `mvault-admin revoke <device>`. Every debug pairing test (debug app paired as `xiaomi-debug-N` etc.) ends the same way on the server: `mvault-admin delete-vault --yes <vault it uploaded>` (check with `mvault-admin stats`: only vaults whose devices are all debug/test names) and `mvault-admin revoke <device>`. Deleted vaults stay in the root-only trash for 30 days (`mvault-admin trash`).
- Server unit tests run on the server in a `mktemp -d` dir as `nobody` (see Lessons learned).

## 4. Key decisions (details in DECISIONS.md)
- Kotlin + Jetpack Compose, minSdk 28, app ID `com.spirtosan.mobilevault`, GPL-3.0.
- Vault format **PVAULT2**: random DEK (rotated on recovery-key changes and on request when changing the password, Task 08); slots = password (Argon2id, calibrated per phone, 64 MiB/t3/p4 on the Xiaomi, NFC-normalised) + **optional** recovery key (160-bit, 36 chars Crockford Base32 + checksum, HKDF; owner's choice 2026-11-02); header = GCM AAD; optional plaintext hint; `rev` counter; entry UUIDs + tombstones. Reads desktop **PVAULT1** for import.
- Backups: **only the own server**, over HTTPS 443 through the existing nginx. Append-only API (no delete/overwrite); mTLS client cert (key in Android Keystore) + bearer token; QR pairing (`mvault-admin pair <name>`, payload has URL, one-time code and SPKI pins incl. the backup pin); `revoke` for lost phones. Retention: last 30 + weekly for 52 weeks; hourly root-owned mirror to the 2nd disk.
- No Google Drive, no SFTP, SSH is LAN-only (not port-forwarded).
- Master password: minimum 6 characters, orange warning below 10 or below "Fair" (owner's choice 2026-11-02).
- UI: dark, desktop palette with colour meaning (orange main action, green add, blue edit, red delete/lock, teal/steel/purple copy, amber show, violet import/new).

## 5. Next steps
### Test phones (answered 2026-10-30)
Huawei Android 10 (API 29, **probably no Google services**, so no GMS/ML Kit/Firebase anywhere; use ZXing for QR), Motorola Android 12 (API 31), Xiaomi Android 16 (API 36, edge-to-edge, aggressive autostart limits; needs "Install via USB" + Mi account for adb installs). Android Studio is installed on the PC (JDK 21 in its `jbr`, not on PATH).

### Done: M2 (Task 05) and Task 06
App MVP on the Xiaomi; Task 06 fixed the recovery-key bug, made the recovery key optional, lowered the master-password minimum to 6 and restyled the UI. Phone checklist results in `STATUS.md` (items 7, 10 and the desktop GUI part of 12 need the owner). Huawei and Motorola still to test with the current build.

### Done: Task 07 = M3 (2026-11-03): backup client, biometric unlock, reminders
Pairing (QR or pasted text → Keystore EC P-256 key → CSR → `POST /pair`), OkHttp 4.12 with mTLS + pins, WorkManager uploads with sha256 verification, health chip, restore (Vault ID → revisions → password/recovery key), version history (rollback as new rev), test restore, fingerprint unlock, practice-password and Emergency Kit cards. Owner checks still open: see STATUS.md. Only the Xiaomi is tested (owner, 2026-11-03).

### Done: Task 08 (2026-11-03): fixes from `docs/CODE_REVIEW_01.md`
`GET /api/v1/vaults` + vault list on the restore screen; **DEK rotation** on new/removed recovery key and (optional) password change; TLS retry; create/test-restore guards; no pinless pairing in release; client-cert expiry warning; `mvault-admin list` shows cert expiry. Live tests that need the test vault password are still open (STATUS.md).

### Done: Task 09 (2026-11-03): simpler Emergency Kit step + one-click pairing
Kit step: "Save Emergency Kit as PDF" or "I wrote it down" (no typing test); Settings → Security → "Check my recovery key". Pairing a phone: double-click `tools/windows/Pair phone.cmd` on the owner's PC (SSH login in `%USERPROFILE%\mobilevault-secrets\pair-phone.json`), scan the full-screen QR, Test connection. Fingerprint checks skipped by the owner's decision (reopen only if a problem shows up).

### Done: Task 10 (2026-11-03): sections in one vault + import into a section
One vault with optional sections (`sec` + `settings.sections`, PVAULT2 v2 unchanged otherwise); chips on the list only when sections exist; Settings → Sections; Settings → Import a file (desktop or Mobile Vault) or from the backup server into a chosen section. Screenshots in `docs/screenshots/task10/`.

### Done with the owner (2026-11-03): release app switched to the real vault
The release app now holds the owner's real vault (sections Personal / Work, new master password, rotated key, new Emergency Kit). **It is off-limits for tests from now on**: only install newer release builds over it. Details and a found import-preview bug: STATUS.md.

### Next
Task 11 (not started). Owner check: HyperOS battery settings.

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
- Xiaomi/HyperOS: adb taps need "USB debugging (Security settings)" **and** USB debugging toggled off/on afterwards. HyperOS may block *new* USB installs ("Block installation") while updates still work.
- The installed release app holds the owner's REAL vault (since 2026-11-03): never test with it, never clear its data. It is signed with the PC's debug keystore. Sign new release builds with the same key and `adb install -r`: a different key means uninstall = vault lost. Check before installing.
- `connectedDebugAndroidTest` uninstalls the debug app (and its data) afterwards.
- Compose UI tests: right after typing, the keyboard animation can move a button and an injected tap misses; click via `performSemanticsAction(OnClick)`.
- `Session.lockEvents` fire even when nothing is open; only reset screens when a vault, kit or import was actually open. Save a new vault/recovery key **before** showing the key.
- Windows working copy has mixed CRLF/LF files: edit with tools that keep the line endings.
- Python on Windows reads files as cp1252 by default: use `encoding='utf-8'` (or `PYTHONUTF8=1`) and `newline=''` when editing sources.
- `AppViewModel`: the `init` block is last on purpose. viewModelScope runs on Main.immediate, so coroutines started in `init` touch properties declared below it.
- adb UI checks: debug builds expose test tags as resource ids (`uiautomator dump`); dialogs don't, so find them by text. Type into fields with a URI keyboard (`input text`), otherwise HyperOS's keyboard auto-capitalises and adds spaces.
- Never commit screenshots of the Backup screen: they show the real server name.
- Git Bash rewrites `/sdcard/...` in adb arguments into Windows paths: set `MSYS_NO_PATHCONV=1` for adb shell commands. The server's Python tests can't run on Windows (`os.link`, directory fsync): run them on the server in a `mktemp -d` dir as `nobody`.
- Compose dialogs: don't clear a field from an async "wrong password" callback; the user may already be typing again. Clear it on click instead.
