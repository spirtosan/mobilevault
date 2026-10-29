# Decisions log

| Date | Decision | Notes |
|---|---|---|
| 2026-10-29 | **Licence: GPL-3.0** | Same as the desktop app; non-commercial, open source. Lets code move freely between desktop and mobile. Any fork must stay open too. |
| 2026-10-29 | **Backup/sync target: own Ubuntu server only** | Google Drive dropped. Protocol (SFTP or WebDAV over HTTPS) to be chosen after the server check (`docs/SERVER_CHECK.md`). Server keeps an append-only archive the phone cannot delete. |
| 2026-10-29 | **Master password reminder** | (a) optional hint shown on the unlock screen, (b) periodic "confirm your master password" prompts when biometrics are used, (c) recovery key as the final fallback. See ANDROID_APP_SPEC.md. |
| 2026-10-29 | App ID `com.spirtosan.mobilevault` (proposed) | Change before the first release if desired. |
| 2026-10-29 | GitHub repo `spirtosan/mobilevault` | |
