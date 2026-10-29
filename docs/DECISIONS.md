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
