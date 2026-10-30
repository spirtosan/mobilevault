# Vault format "PVAULT2" (proposed)

Goals: a recoverable master password (recovery key), an authenticated header, entry IDs for sync, versioning for backups, and full read support for PVAULT1.

## Envelope encryption
- **DEK**: a random 32-byte data key. It encrypts the payload and never changes when the password changes.
- **Key slots**: the DEK is wrapped (AES-256-GCM) by one or more KEKs:
  - `password` slot: KEK = Argon2id(master password, salt)
  - `recovery` slot: KEK = HKDF-SHA256(recovery key, salt, info "PVAULT2 recovery"). The recovery key is **160 random bits**, shown once on a printable **Emergency Kit** as 32 Crockford-Base32 characters + a 4-character checksum, in 9 groups (`XXXX-XXXX-…`, 44 characters; O/I/L are read as 0/1/1). A slow KDF isn't needed because the key is fully random.
  - `biometric` slot: this one is **not stored in the file**. It's stored locally, with the DEK wrapped by an Android Keystore key that requires `BiometricPrompt` (StrongBox if available).
- Changing the password only rewrites the password slot. Losing the password means unlocking with the recovery key and setting a new password.

## Binary layout
```
0    8   MAGIC "PVAULT2\0"
8    4   header_len (uint32, big-endian)
12   h   header (UTF-8 JSON, see below)
12+h 12  body nonce
...  n   AES-256-GCM(DEK, payload_json) || tag      AAD = bytes[0 .. 12+h)
```
Using the whole header as AAD means nobody can change the revision, slots or KDF parameters without detection.

### Header JSON
```json
{
  "v": 2,
  "vault_id": "uuid-v4",
  "rev": 42,
  "updated": "2026-10-29T19:07:00Z",
  "device": "pixel8-3f2a",
  "hint": "optional master password hint (plaintext!)",
  "slots": [
    {"type": "password", "kdf": {"alg": "argon2id", "m_kib": 65536, "t": 3, "p": 4, "salt": "b64"},
     "nonce": "b64", "wrapped_dek": "b64"},
    {"type": "recovery", "kdf": {"alg": "hkdf-sha256", "salt": "b64"},
     "nonce": "b64", "wrapped_dek": "b64"}
  ]
}
```
`hint` is optional plaintext, readable without the password; it is tamper-proof because the header is the AAD. See ANDROID_APP_SPEC.md → Master password reminder.

Slot wrapping: `AES-GCM(KEK, DEK, aad = "PVAULT2-slot:" + type + ":" + vault_id)`.
Passwords are Unicode **NFC-normalised** before UTF-8 encoding (different keyboards produce the same key). Argon2id defaults are 64 MiB, t=3, **p=4** (since 2026-10-30; earlier files have p=1 and still open, any p in 1–16 is read). When *reading*, files with less than 32 MiB / t=2 are refused (protection against a downgraded header). At vault creation the app picks the strongest of 64/32 MiB × t3/t2 (p=4) whose unlock takes ≤ 1 s on the device, never below 32 MiB, t=2 (see the Argon2 table in DECISIONS.md).
The recovery slot has no Argon2: the key is 160 random bits, so HKDF is enough (see above).
Implementations: Android uses `core-crypto`'s own pure-Kotlin Argon2id (`Argon2.kt`: flat memory, lanes on parallel threads; byte-identical to RFC 9106, BouncyCastle and argon2-cffi). Desktop uses `argon2-cffi` (`hash_secret_raw`), which handles p=4 natively.

### Payload JSON
```json
{
  "entries": [
    {"id": "uuid", "s": "GitHub", "u": "me", "p": "secret", "url": "https://…",
     "notes": "", "tags": [], "fav": false,
     "created": "ISO-8601", "modified": "ISO-8601", "deleted": false}
  ],
  "settings": {"clipboard_timeout": 30, "autolock_seconds": 300}
}
```
- The `s/u/p/url` keys are kept from v1, so converting is trivial.
- `deleted: true` marks a tombstone, kept for 90 days so that a merge doesn't bring deleted entries back.
- `rev` goes up by 1 on every save. Merging between devices uses `id` and `modified`.

## Compatibility
- Mobile **reads** PVAULT1 (import from desktop). Importing converts to PVAULT2, gives every entry a UUID, and sets timestamps to the import time.
- Mobile can **export** a PVAULT1 file so the current desktop app can still open it (this drops notes/tags/history).
- Recommended: add PVAULT2 read/write to the desktop app later, so both can share the same backup location.
