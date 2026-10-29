# Mobile Vault (Android)

An Android password vault based on **Shu PassVault** (desktop). It keeps the same strong offline encryption and adds protection against **losing** data: versioned, zero-knowledge remote backups to your own Ubuntu server, a master password hint and reminders, a recovery key for a forgotten master password, and local snapshot history.

## Folder contents
| Path | What |
|---|---|
| `docs/DECISIONS.md` | Decisions made so far (licence, backup targets, password reminder) |
| `docs/CLAUDE_CODE_TASK_01.md` | Task for Claude Code: initial push + read-only server check |
| `docs/DESKTOP_APP_ANALYSIS.md` | How the desktop app works, the exact PVAULT1 file format, and weaknesses to fix |
| `docs/ANDROID_APP_SPEC.md` | Tech stack, features, security hardening, screens, milestones, open decisions |
| `docs/VAULT_FORMAT_V2.md` | New file format: envelope encryption, recovery key, authenticated header, entry IDs |
| `docs/BACKUP_AND_RECOVERY.md` | Remote backup design (WebDAV / SAF / S3), retention, restore, conflicts, Emergency Kit |
| `reference/desktop-passvault/` | Copy of the desktop source, README, licence and screenshots (for UI reference) |
| `assets/` | App icon (`icon.png` 1.3 MB source and `icon.ico`); generate Android adaptive icons from `icon.png` |
| `test-vectors/` | Sample desktop-format vault + expected plaintext for unit tests |
| `tools/make_pvault1_sample.py` | Regenerates the sample vault (needs `pip install cryptography`) |

Not copied: `build/` and `dist/` (PyInstaller output, ~37 MB, Windows-only, not useful for Android).

Licence: GPL-3.0 (inherited from the desktop app).
