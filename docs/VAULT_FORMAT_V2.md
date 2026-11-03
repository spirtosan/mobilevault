# Vault format "PVAULT2" (proposed)

Goals: a recoverable master password (recovery key), an authenticated header, entry IDs for sync, versioning for backups, and full read support for PVAULT1.

## Envelope encryption
- **DEK**: a random 32-byte data key. It encrypts the payload and doesn't change on a plain password change. It changes only on a **DEK rotation** (below).
- **Key slots**: the DEK is wrapped (AES-256-GCM) by one or more KEKs:
  - `password` slot: KEK = Argon2id(master password, salt)
  - `recovery` slot: KEK = HKDF-SHA256(recovery key, salt, info "PVAULT2 recovery"). The recovery key is **160 random bits**, shown once on a printable **Emergency Kit** as 32 Crockford-Base32 characters + a 4-character checksum, in 9 groups (`XXXX-XXXX-…`, 44 characters; O/I/L are read as 0/1/1). A slow KDF isn't needed because the key is fully random.
  - `biometric` slot: this one is **not stored in the file**. It's stored locally, with the DEK wrapped by an Android Keystore key that requires `BiometricPrompt` (StrongBox if available).
- Changing the password only rewrites the password slot. Losing the password means unlocking with the recovery key and setting a new password.
- The `recovery` slot is **optional** (owner's choice, 2026-11-02). A vault created with "Skip" has only the `password` slot; one can be added or removed later (Settings → Recovery key). Readers look slots up by `type` and must not assume a recovery slot exists. Without it, a forgotten password cannot be recovered by anyone.

## DEK rotation (Task 08)
Re-wrapping the same DEK is not enough when a credential may be compromised: every older copy of the file (server revisions, local snapshots, desktop exports) still has the old slots, and the old DEK would also decrypt every **future** revision. So these actions **rotate** the DEK (`UnlockedVault.rotateDek(password, newRecoveryKey, kdf)`):
- a new recovery key (Settings, "Create recovery key", the yearly "make a new kit" card); the master password is asked first,
- removing the recovery key,
- changing the master password with "Someone may know my old password" ticked (a vault with a recovery key also gets a new one).

Rotation creates a new random DEK, wipes the old one, writes a new `password` slot (fresh salt) and, if asked, a new `recovery` slot; all old slots are dropped. `vault_id`, `rev` and `hint` stay; the next `seal()` encrypts the body with the new DEK. The format doesn't change (still v2).
Consequences: older files still open with the credentials and DEK they were saved with (unavoidable with append-only backups), but they only contain old data. The local fingerprint slot wraps the old DEK, so the app turns fingerprint unlock off after a rotation. Version history asks for "the master password this version was saved with" when a revision doesn't open with the current DEK.

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
