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
| 2026-10-30 | **Test phones: Huawei Android 10, Motorola Android 12, Xiaomi Android 15** | minSdk 28 covers all. **No Google Play services dependencies** (the Huawei probably has none): QR via ZXing, no Firebase/ML Kit. Background uploads must cope with vendor battery killers (also upload on app open). |
| 2026-10-30 | **Argon2 parameters calibrated per phone at vault creation** | Strongest of 64/32 MiB × t3/t2 with unlock ≤ 1 s, never below 32 MiB/t2; must still open on the slowest phone within ~3 s. Benchmark table: TBD (Task 05 step 1). |
| 2026-10-30 | **M2 implemented by local Claude Code** (Task 05) | It has the Android SDK and the phones for the build/test loop; the cloud session reviews. |
| 2026-10-30 | **SSH password login re-enabled for all users, including root** | Owner's choice (PuTTY with a password). Reverses the key-only part of the 2026-10-29 hardening. Acceptable only because SSH is not port-forwarded and ufw allows it from the LAN only. Details: SERVER_CHECK.md. |
