# Desktop app analysis — Shu PassVault v1.3

Source: `C:\Users\spirt\passvault` (copied to `reference/desktop-passvault/`).
Single file, `password_manager.py` (~1,270 lines), Python 3.10+, Tkinter UI and the `cryptography` package. GPL-3.0.

## What it does
- Opens or creates one `.vault` file, chosen by the user with a file dialog.
- Each entry has 4 fields: **Service** `s` (required), **Username** `u`, **Password** `p` (required), **URL** `url`.
- UI features: sortable table, real-time search (Service/Username/URL), show/hide a password, copy username/password/URL (copying the URL also opens it in a browser), a password strength meter, a Caps Lock warning, and change master password.
- Settings live in `%APPDATA%\ShuPassVault\config.json`: `{"clipboard_timeout": 30}` (seconds, 0 = never).

## Security behaviour
| Item | Desktop implementation |
|---|---|
| Encryption | AES-256-GCM over the whole JSON payload |
| KDF | PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt |
| Nonce | 12 bytes random, new on every save |
| Salt | new on every save (so the key is re-derived on each save) |
| Saving | write `<file>.tmp`, then `os.replace` (atomic) |
| Local backup | `<file>.bak` copied after each successful save (only 1 generation) |
| Auto-lock | 5 min of no key/mouse activity, which wipes entries and the password from memory |
| Clipboard | password cleared after the timeout (default 30 s) |
| Network | none (100% offline) |

## File format "PVAULT1" (must be readable by the mobile app)
```
offset  len  field
0       8    MAGIC = "PVAULT1\0"  (50 56 41 55 4C 54 31 00)
8       16   salt
24      12   nonce
36      n    AES-GCM ciphertext || 16-byte tag   (no AAD)
```
key = PBKDF2-HMAC-SHA256(password UTF-8, salt, 600000, 32 bytes)
plaintext (UTF-8 JSON) = `{"entries": [{"s": "...", "u": "...", "p": "...", "url": "..."}, ...]}`

Java/Kotlin equivalent: `SecretKeyFactory("PBKDF2WithHmacSHA256")` + `Cipher("AES/GCM/NoPadding")` with `GCMParameterSpec(128, nonce)`. Python's `AESGCM` appends the tag to the ciphertext the same way Java does, so the bytes from offset 36 onward can go straight to `cipher.doFinal()`.
A known-answer sample is in `test-vectors/`.

## Weaknesses to fix in the mobile app
1. **Single point of failure.** One file plus one `.bak` next to it. If the disk is lost, both are lost. There is no off-device backup.
2. **The master password can't be recovered.** Forgetting it means losing all data, and there is no recovery key.
3. **Only one backup generation.** A bad save (for example, after the user accidentally deleted entries) overwrites the only `.bak` on the next save.
4. **No entry IDs or timestamps.** This makes syncing and merging between devices impossible. Entries are addressed only by list index.
5. **PBKDF2 is not memory-hard.** Argon2id is the modern recommendation.
6. **The header is not authenticated** (no AAD). The format has no version field beyond the magic.
7. The password is kept as an immutable Python `str` (it can't be wiped), and the UI-side password strength check is very simple.
8. No notes field, no categories or favourites, and no password generator.
