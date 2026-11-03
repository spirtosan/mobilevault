# Project status: 2026-11-03

> Starting a new session? Read `docs/HANDOFF.md` first.

## Done
| Area | Result | Commit |
|---|---|---|
| Analysis + specs | Desktop app analysed; Android spec, PVAULT2 format, backup design, decisions log (`docs/`) | 23e811d… |
| Server hardening | SSH key + password (password login re-enabled for all users incl. root on 2026-10-30, owner's choice; LAN-only), ufw firewall (SSH/Samba/Node LAN-only), fail2ban (sshd + mvault), fstab UUID+nofail | dc128b3, 752f283 |
| Web exposure fix | nginx denies dotfiles, sensitive file types, tracker-dist, shunotes (except /shunotes/privacy/); tracker secrets rotated | – (server only) |
| Backup server | `server/`: append-only HTTPS API at `https://vault.<DOMAIN>/api/v1`, mTLS + bearer token, pairing by QR (`mvault-admin pair <name>`), revoke, daily retention, hourly root-owned mirror to the 2nd disk. Smoke test 9/9 | c21d268 |
| M1 core-crypto | `android/core-crypto`: PVAULT1 import/export, PVAULT2 (password + recovery-key slots, NFC, authenticated header), hint policy. 27/27 tests on the owner's PC; Python reference `android/tools/pvault2.py` cross-checked both ways. Argon2id default create+unlock: 307 ms on PC | bea88f5 |
| Tracker app removed (2026-10-30) | pm2 `tracker` + `tracker-dist` deleted (`pm2 save`); nginx `/tracker`, `/tracker/` and the `/tracker-dist` deny removed (`nginx -t` OK, main site/vault/shunotes verified); ufw LAN rule for 3000/3001 removed (8083 kept as its own rule); both app folders (incl. SQLite DBs) and the tracker files in the 2026-10-29 config backups deleted. One archive kept offline on the owner's PC (outside the repo) | – (server only) |
| M2 Android app MVP (Task 05) | `android/app`: create/unlock/list/edit/settings, auto-lock, clipboard clear, desktop import/export, snapshots, FLAG_SECURE, Argon2 calibrated per phone (own Kotlin Argon2id, p=4) | 5735d34, 561126f, d217ce9, 1d60142 |
| Task 07 = M3 (2026-11-03) | A: pairing (Keystore EC key + CSR, QR scan or paste, pins, mTLS + token, Test connection, Disconnect). B: automatic uploads (WorkManager, verify sha256, 409 handling) + health chip. C: restore on a new phone, version history (rollback as new rev), test restore, create step 3. D: fingerprint unlock (Keystore-wrapped DEK, reboot / N days / 5 failures / new fingerprint). E: practice-password and yearly Emergency Kit cards, debug clock. core-crypto 39 + app 51 unit tests, 9 UI tests on the Xiaomi, lint 0 errors, release built and installed. Live: pairing, wrong pin, reused code, revoke, uploads, airplane mode, rollback, test restore, full new-phone restore; release app paired as `xiaomi`, backup r10 verified + test restore OK | 6d867cc, e312795, 3e6bdb9, f681731, 39befe8 |
| Task 10 (owner decision, 2026-11-03): sections + import into a section | A: `sec` field + `settings.sections`, VaultEdits section functions, `pvault2.py --list`. B: chip row All / Personal / Work (only when sections exist, remembered), search across all sections, Section dropdown in the editor, Settings → Sections (add, rename, reorder, delete with move), list menu "Split into sections", desktop export of one section. C: Settings → Import a file (desktop or Mobile Vault, auto-detected) or from the backup server (another vault + revision), opened in memory with its own password/recovery key; "Import into section" existing / new / none, "Keep its sections". core-crypto 43 + app 81 unit tests, 13 UI tests, lint 0 errors, release installed over the existing app (data kept, permissions unchanged). Live: test vault from the server into "Work-test" (5), desktop sample into "Desktop sample" (3). Screenshots `docs/screenshots/task10/` | 549ac7e, fb9afb3, f10ab6c |
| Task 09 (owner feedback, 2026-11-03) | A: Emergency Kit step without the typing test: "Save Emergency Kit as PDF" (success completes the step) or "I wrote it down"; reworded "not confirmed" card; Settings → Security → "Check my recovery key" (try-unlock in memory, throttled). B: one-click pairing from the PC: `tools/windows/Pair phone.cmd` (SSH login in `%USERPROFILE%\mobilevault-secrets\pair-phone.json`, QR full screen with countdown, image only in memory). core-crypto 42 + app 67 unit tests, 12 UI tests on the Xiaomi, lint 0 errors, release installed over the existing app (data kept, permissions unchanged). Live: kit "I wrote it down" + cancelled PDF picker, key check ✓/✗/typo; pairing script scanned by the owner with the debug app, Test connection OK, then revoked | ad70160, f9bbad4 |
| Task 08 (code review 01, 2026-11-03) | A: `GET /api/v1/vaults` (server deployed, smoke test 11/11) + restore screen lists the server's vaults (manual Vault ID as fallback, used on 404), Vault ID with copy button in Settings → Backup. B: DEK rotation (`UnlockedVault.rotateDek`) for new/first recovery key (password first), remove key, and "Someone may know my old password" on password change; fingerprint off after rotation; honest texts; test restore says "still uploading" right after a rotation. C: TLS failures retried (red chip from 3 in a row), no `createVault` over an existing vault, no test restore while locked, no pinless pairing in release, client-cert expiry in Settings + amber chip 30 days ahead, `mvault-admin list` shows cert expiry (deployed), fingerprint key needs an unlocked device. core-crypto 42 + app 66 unit tests, server 11 tests, 9 UI tests on the Xiaomi, lint 0 errors, release built and installed over the existing app (same key, data kept, permissions unchanged) | 4e50ba2, 1a37c70, 8b9dbcc, 92843d0 |
| Task 06 (owner feedback, 2026-11-02) | A: recovery-key bug fixed (a lock on the Emergency Kit screen discarded the unsaved vault after its PDF was saved; every lock reset locked-side screens and erased the typed key). B: recovery key optional (skip / add / remove). C: master password min 6 chars, warning below 10 or "Fair". D: desktop-style coloured UI (`docs/screenshots/task06/`). core-crypto 38 + app 16 unit tests, 9 UI tests on the Xiaomi | d1deddc, 7b36eb5, 7b768dd, 67ce83d |

## Open to-dos (owner)
- [x] ~~Copy `vault-spare.key` to an offline USB stick~~ → done 2026-10-30
- [x] ~~Change the 3 tracker app passwords~~ → owner decided (2026-10-30) to **remove the tracker app** from the server entirely
- [x] ~~Check the old tracker seed passwords aren't reused anywhere else~~ → done 2026-10-30
- [x] ~~Test `sudo -v` as the admin user~~ → works (2026-10-30)

## Open to-dos (later tasks)
- [x] ~~Remove tracker + tracker-dist completely (pm2, nginx /tracker/, ufw 3000/3001, folders, backups); archive kept offline~~ → done 2026-10-30
- [ ] TURN server `external-ip` is stale (old public IP), so the other app's relay is probably broken
- [ ] Server's own backup: restic to the 2nd disk + an off-site copy + a monthly test restore
- [ ] 3 pending non-security apt updates; remove dead nginx locations /fshu/ and /fshu3/
- [ ] Optional later: `PermitRootLogin no` (switch Claude Code to admin user + sudo)

## Phone checklist (Task 05 items 4–13), Xiaomi Android 16, release build, 2026-11-03
| # | Item | Result |
|---|---|---|
| 4 | Wrong password + hint | ✓ "Wrong password", then "Show hint" shows the hint |
| 5 | Recovery-key unlock + new password | ✓ incl. leaving the app mid-typing (PDF viewer) and on the forced new-password screen |
| 6 | Add / edit / delete / search | ✓ |
| 7 | Copy password, clipboard cleared after the timeout | owner to check (adb can't read the clipboard on Android 16) |
| 8 | Auto-lock after idle | ✓ (1 min setting, locked after 70 s; set back to 5 min) |
| 9 | Lock when switching apps | ✓ |
| 10 | Screenshot blocked | ✓ adb screencap is black in release; owner to confirm with the hardware buttons |
| 11 | Desktop import of the sample file | ✓ 3 entries merged |
| 12 | Export for desktop opens in the desktop app | ✓ decrypted by the desktop app's own `decrypt_vault` (5 entries); opening in the GUI: owner |
| 13 | Airplane mode makes no difference | ✓ (and no INTERNET permission) |

Huawei (Android 10) and Motorola (Android 12) not tested with the Task 06 build yet.

## Task 07: owner checks still open (2026-11-03)
- [x] ~~Pair by **scanning the QR code** with the camera~~ → done 2026-11-03 (debug app as `xiaomi-qr`, Test connection OK, then disconnected + revoked)
- ~~Fingerprint checks~~ → **skipped by the owner's decision (2026-11-03)**; reopen only if a problem shows up.
- [ ] HyperOS: Autostart on + Battery saver "No restrictions" for Mobile Vault; then check the chip stays green after a day without opening the app.
- Server devices now: `xiaomi` (release app) active; `xiaomi-debug`, `xiaomi-debug-2`, `xiaomi-debug-3`, `xiaomi-debug-4`, `xiaomi-qr`, `RamboPhone` (Task 09 pairing test), `xiaomi-debug-5` (Task 10 import test), `smoketest`, `smoketest2` revoked. Debug test vaults `dccf07c8-…`, `c3690f79-…` and `baa39212-…` were moved to the trash on 2026-11-03 (Task 11 B, purged after 2026-12-03), together with the smoke-test vaults of `smoketest3`–`smoketest5` (also revoked). On the server now: only `274cdb88-…` (`xiaomi`).

## Task 08: open
- [x] ~~Live tests~~ → done 2026-11-03: debug app restore from the vault list (fresh pairing, revoked after); release app new recovery key (password asked first) → r11 uploaded → fingerprint unlock turned off → test restore "r11 decrypts: 5 entries, same as on this phone" → version history r10 asks for "the master password this version was saved with" and opens; chip green.
- [ ] Owner: after any key change, turn fingerprint unlock on again (Settings → Security). The new fingerprint key also requires an unlocked phone (L2).

## Release app now holds the owner's REAL vault (2026-11-03)
- Done with the owner, step by step: backup verified; 5 test entries deleted; new master password (typed by the owner); vault key rotated with a new recovery key (Emergency Kit PDF saved on the phone by the owner); sections Personal + Work; the owner's personal and work desktop vaults imported into them (temporary copies on the phone deleted afterwards; the source files are untouched). Revision 22 uploaded, chip green, test restore OK. Fingerprint unlock is off (the owner turns it on).
- **Permanent rule:** **The release app (`com.spirtosan.mobilevault`) holds the owner's real vault.** Never open, unlock, read, back up, restore over, clear, uninstall, re-pair or disconnect it, and never take screenshots of it. The only exception is installing a newer release build **over** it (same signing key, `adb install -r`), which keeps its data. All tests use the **debug app** (`com.spirtosan.mobilevault.debug`) with test data only. The old test password is retired; don't use it on the release app. On the server, the device `xiaomi` (the release app) stays active and untouched, and the owner's real vault `274cdb88-…` is never deleted, trashed, restored or read.
- Found during the import (fix in the next task): on the import preview, "New section…" is preselected with a name taken from the file ("personal.vault" → "Personal"); if that section already exists, Import fails with "already exists" (only a short snackbar, looks like nothing happened). Fix: preselect the existing section with that name.
- Next: Task 11 (`docs/CLAUDE_CODE_TASK_11.md`, not started yet; the owner approved its server change for Part A only, and in Part B only the debug test vaults may be deleted on the server, never the `xiaomi` vault or old personal/work vaults).

## Next
- Owner check left: HyperOS battery settings. Then Huawei/Motorola when wanted. Later: autofill, multi-device merge, PVAULT2 in the desktop app, the "Later" items of `CODE_REVIEW_01.md` (L1, L3–L6).

## How work is done
- The cloud Claude session writes code/docs into `C:\Users\spirt\mobilevault` (no GitHub access from there). The local **Claude Code** session builds, deploys to the server and pushes to GitHub using `docs/CLAUDE_CODE_TASK_NN.md` files (01–10 done).
- The repo is **public**: placeholders only (`<DOMAIN>`, `<DATA_DISK>`, `<MIRROR_DISK>`, `<LAN_SUBNET>`, `<ADMIN_USER>`). Real values live only on the server and in chat.
- `*.tgz` in the project folder are transfer leftovers (git-ignored) and can be deleted.
- **2026-11-03:** owner decision: test on the Xiaomi only for now (Motorola/Huawei skipped). Task 07 (M3: backups, restore, biometrics, reminders) written: `docs/CLAUDE_CODE_TASK_07.md`.
