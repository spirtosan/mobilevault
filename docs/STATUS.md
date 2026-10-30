# Project status: 2026-10-30

## Done
| Area | Result | Commit |
|---|---|---|
| Analysis + specs | Desktop app analysed; Android spec, PVAULT2 format, backup design, decisions log (`docs/`) | 23e811d… |
| Server hardening | SSH key-only (root: key only), ufw firewall (SSH/Samba/Node LAN-only), fail2ban (sshd + mvault), fstab UUID+nofail | dc128b3, 752f283 |
| Web exposure fix | nginx denies dotfiles, sensitive file types, tracker-dist, shunotes (except /shunotes/privacy/); tracker secrets rotated | – (server only) |
| Backup server | `server/`: append-only HTTPS API at `https://vault.<DOMAIN>/api/v1`, mTLS + bearer token, pairing by QR (`mvault-admin pair <name>`), revoke, daily retention, hourly root-owned mirror to the 2nd disk. Smoke test 9/9 | c21d268 |
| M1 core-crypto | `android/core-crypto`: PVAULT1 import/export, PVAULT2 (password + recovery-key slots, NFC, authenticated header), hint policy. 27/27 tests on the owner's PC; Python reference `android/tools/pvault2.py` cross-checked both ways. Argon2id default create+unlock: 307 ms on PC | bea88f5 |
| Tracker app removed (2026-10-30) | pm2 `tracker` + `tracker-dist` deleted (`pm2 save`); nginx `/tracker`, `/tracker/` and the `/tracker-dist` deny removed (`nginx -t` OK, main site/vault/shunotes verified); ufw LAN rule for 3000/3001 removed (8083 kept as its own rule); both app folders (incl. SQLite DBs) and the tracker files in the 2026-10-29 config backups deleted. One archive kept offline on the owner's PC (outside the repo) | – (server only) |

## Open to-dos (owner)
- [ ] Copy `C:\Users\spirt\mobilevault-secrets\vault-spare.key` to a USB stick, keep it offline (TLS pin-rotation spare key)
- [x] ~~Change the 3 tracker app passwords~~ → owner decided (2026-10-30) to **remove the tracker app** from the server entirely
- [ ] Check the old tracker seed passwords (sent in chat 2026-10-30) aren't reused anywhere else
- [ ] Test `sudo -v` as the admin user once (Claude Code couldn't type its password)

## Open to-dos (later tasks)
- [x] ~~Remove tracker + tracker-dist completely (pm2, nginx /tracker/, ufw 3000/3001, folders, backups); archive kept offline~~ → done 2026-10-30
- [ ] TURN server `external-ip` is stale (old public IP), so the other app's relay is probably broken
- [ ] Server's own backup: restic to the 2nd disk + an off-site copy + a monthly test restore
- [ ] 3 pending non-security apt updates; remove dead nginx locations /fshu/ and /fshu3/
- [ ] Optional later: `PermitRootLogin no` (switch Claude Code to admin user + sudo)

## Next: M2 (Android app MVP)
- Android Studio is installed on the PC (its bundled JDK 21 was used for the tests). Java isn't on PATH.
- Still to answer: **which phone / Android version** to test on.
- Scope (see ANDROID_APP_SPEC.md): `app` module (Compose, minSdk 28), create/unlock/list/edit, auto-lock, clipboard clear, desktop import, FLAG_SECURE; tune Argon2 on the phone (target 0.5–1 s).
- Then M3: backup client (pairing QR → Keystore key + CSR → `/pair`, upload queue via WorkManager, restore, health indicator), Emergency Kit, password reminders.

## How work is done
- The cloud Claude session writes code/docs into `C:\Users\spirt\mobilevault` (no GitHub access from there). The local **Claude Code** session builds, deploys to the server and pushes to GitHub using `docs/CLAUDE_CODE_TASK_NN.md` files (01–04 done).
- The repo is **public**: placeholders only (`<DOMAIN>`, `<DATA_DISK>`, `<MIRROR_DISK>`, `<LAN_SUBNET>`, `<ADMIN_USER>`). Real values live only on the server and in chat.
- `*.tgz` in the project folder are transfer leftovers (git-ignored) and can be deleted.
