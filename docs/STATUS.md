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
- [ ] Fingerprint: turn on (password + finger), lock, unlock with the finger; turn off.
- [ ] Fingerprint: reboot → password required; enrol a new fingerprint → "Fingerprints changed", then turn on again; 5 wrong fingers → password.
- [ ] HyperOS: Autostart on + Battery saver "No restrictions" for Mobile Vault; then check the chip stays green after a day without opening the app.
- Server devices now: `xiaomi` (release app) active; `xiaomi-debug`, `xiaomi-debug-2`, `xiaomi-debug-3`, `xiaomi-debug-4`, `xiaomi-qr`, `smoketest`, `smoketest2` revoked. Test vault `dccf07c8-…` (debug, 4 revs) stays on the server until retention.

## Task 08: open
- [x] ~~Live tests~~ → done 2026-11-03: debug app restore from the vault list (fresh pairing, revoked after); release app new recovery key (password asked first) → r11 uploaded → fingerprint unlock turned off → test restore "r11 decrypts: 5 entries, same as on this phone" → version history r10 asks for "the master password this version was saved with" and opens; chip green.
- [ ] Owner: after any key change, turn fingerprint unlock on again (Settings → Security). The new fingerprint key also requires an unlocked phone (L2).

## Next
- Task 08 live tests (above), owner checks; then Huawei/Motorola when wanted. Later: autofill, multi-device merge, PVAULT2 in the desktop app, the "Later" items of `CODE_REVIEW_01.md` (L1, L3–L6).

## How work is done
- The cloud Claude session writes code/docs into `C:\Users\spirt\mobilevault` (no GitHub access from there). The local **Claude Code** session builds, deploys to the server and pushes to GitHub using `docs/CLAUDE_CODE_TASK_NN.md` files (01–08 done).
- The repo is **public**: placeholders only (`<DOMAIN>`, `<DATA_DISK>`, `<MIRROR_DISK>`, `<LAN_SUBNET>`, `<ADMIN_USER>`). Real values live only on the server and in chat.
- `*.tgz` in the project folder are transfer leftovers (git-ignored) and can be deleted.
- **2026-11-03:** owner decision: test on the Xiaomi only for now (Motorola/Huawei skipped). Task 07 (M3: backups, restore, biometrics, reminders) written: `docs/CLAUDE_CODE_TASK_07.md`.
