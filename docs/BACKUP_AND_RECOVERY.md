# Backup & recovery design

Main principle: **zero-knowledge.** Only the already-encrypted vault file ever leaves the phone. Backup servers never see the password, the DEK or the plaintext, so a compromised server can't expose the data.

## Layers of protection against data loss (3-2-1 rule)
| # | Layer | Protects against |
|---|---|---|
| 1 | Atomic save (write tmp, fsync, rename) | crash or battery dying mid-write |
| 2 | Local snapshot history in app-private storage: last 20 saves + 1 per day for 30 days | accidental deletion, bad edits |
| 3 | **Remote backup** to 1 or more destinations, versioned | phone lost, stolen, broken or reset |
| 4 | Recovery key / Emergency Kit (paper) | forgotten master password |
| 5 | Manual export (encrypted file, share sheet) | everything else, user-controlled |

## Remote destinations (pluggable `BackupTarget` interface)
```kotlin
interface BackupTarget {
    suspend fun upload(name: String, bytes: ByteArray): RemoteFile
    suspend fun list(): List<RemoteFile>       // name, size, modified, sha256?
    suspend fun download(name: String): ByteArray
    // no delete(): remote targets are append-only; retention is the server's job
}
```
Chosen target (see DECISIONS.md): **the owner's Ubuntu server only** (Google Drive dropped).

**Chosen transport: HTTPS through the existing nginx (port 443, Let's Encrypt).** No SSH or other new port is exposed to the internet, and it works on any network. (SFTP was considered first; it is no longer needed because 443 is already public.)

### Server: "mvault" backup API (append-only)
Base URL: `https://vault.<DOMAIN>/api/v1` (a separate subdomain, so client-certificate checks don't affect the other sites; fallback `https://<DOMAIN>/mvault/api/v1`).

| Method | Path | Result |
|---|---|---|
| `PUT` | `/vaults/{vault_id}/revs/{rev}` | Body = PVAULT2 file, header `X-Content-SHA256`. **201** stored; **409** this rev already exists (never overwrites); **413** > 5 MB; **422** bad magic, hash mismatch, or `vault_id`/`rev` not matching the file header |
| `GET` | `/vaults/{vault_id}/revs` | JSON list `[{rev, size, sha256, received}]` |
| `GET` | `/vaults/{vault_id}/revs/{rev}` | the file |
| `GET` | `/vaults/{vault_id}/latest` | metadata of the highest rev (used for conflict detection) |
| `GET` | `/health` | `{"ok":true,"version":"1"}` |

- **There is no DELETE and no overwrite.** A stolen phone, a bug or a leaked token can only *add* files, never remove history. Retention (last 30 revs + 1 per week for 1 year) is run by a separate root systemd timer.
- **Auth, two factors per device:**
  1. **mTLS client certificate.** A private CA lives on the server. The key pair is generated *on the phone*, inside Android Keystore (non-exportable). nginx `ssl_verify_client on` is set on the vault subdomain only. Certificates can be revoked per device.
  2. `Authorization: Bearer <token>`: 32 random bytes per device. The server stores only its SHA-256.
- **Pairing a new phone:** the admin runs `mvault-pair <device-name>` on the server. It shows a QR code (URL + one-time code, valid 10 min). The phone scans it, sends a CSR plus the code, and gets back its client certificate plus token. Revoke with `mvault-revoke <device>`.
- **Server-certificate pinning:** certbot is run with `--reuse-key` for the subdomain, so the app pins that public-key hash plus a backup key hash (stored offline). Pin mismatch: refuse to send and show an alert.
- **nginx hardening on that subdomain:** `client_max_body_size 5m`, `limit_req` (e.g. 30 requests/min/IP), and only `/api/v1/` is proxied (everything else returns 404). fail2ban watches for repeated 400/401/403 responses.
- **Service:** Python 3 standard library only (no pip dependencies), listening on `127.0.0.1:8790`. It's a systemd unit running as user `mvault`, with `ProtectSystem=strict`, `ReadWritePaths=` limited to the data dir, `NoNewPrivileges=yes`, and `PrivateTmp=yes`. Files are written via `O_CREAT|O_EXCL` + fsync, mode 0440.
- **Storage:** `<DATA_DISK_A>/mobilevault/<vault_id>/r<rev>.pvault`, mirrored hourly to `<DATA_DISK_B>/mobilevault-mirror/` (two physical disks). An off-site copy is part of the server backup plan.
- **Privacy:** the server only ever sees encrypted blobs. The plaintext header reveals vault_id, rev, timestamps and the optional password hint, nothing else.

### Client (Android)
OkHttp with the Keystore-backed client certificate, certificate pinning, and TLS 1.2+ only. Uploads are queued in WorkManager (network constraint, exponential backoff).

The `BackupTarget` interface stays pluggable, so another server or a USB/SD copy (SAF) can be added later. Recommended minimum: the server, plus the paper recovery key, plus an occasional manual export.

## Upload logic
- Each save produces a new `rev`. A `WorkManager` job (network-connected constraint, exponential backoff) uploads `vault-<vault_id>-r<rev>-<utc>.pvault` and then overwrites `vault-<vault_id>-latest.pvault`.
- Integrity: after upload, compare SHA-256 with the server value (ETag, Content-MD5, or a re-download) before marking the upload successful.
- Retention on the remote: keep the last 30 revisions, plus 1 per week for 1 year. Old files are deleted only after a newer upload has been verified.
- Uploads don't need the vault to be unlocked, because the file is already encrypted.
- The **backup health** status is always visible, for example "Last remote backup: 2 h ago ✔". Warn after 7 days without a successful backup, and warn if there are unsynced changes.

## Reaching the server
- The same HTTPS URL works at home and away (`vault.<DOMAIN>`; if the router doesn't support NAT loopback, a LAN fallback URL with the same pinned certificate).
- When no address is reachable, uploads stay queued (WorkManager). Local snapshots still protect the data, and the backup health indicator turns yellow after 3 days and red after 7 days.
- Setting: "Back up only on these Wi-Fi networks" (optional).

## Restore flows
- **New phone / reinstall**: on first launch, choose "Restore from backup", pick a target, list the versions, download, then unlock with the password or recovery key.
- **Rollback**: Settings → Backups → Version history → preview the entry count and date (requires unlock) → restore. The current state is snapshotted first, so a restore can always be undone.
- **Test restore** (monthly reminder): downloads the latest remote copy and decrypts it in memory with the current DEK, which proves the backup really works.

## Multi-device / conflicts
- Before uploading, read the remote `latest` header (plaintext, authenticated after unlock). If `remote.rev > local base rev` and the device differs, there is a conflict.
- Phase 1: never overwrite. Upload as `...-conflict-<device>.pvault` and ask the user to choose or merge.
- Phase 2: automatic merge per entry `id`: newer `modified` wins, and tombstones are respected. Conflicting edits keep both versions.

## Emergency Kit (PDF generated on device, shown at vault creation)
Contains the vault ID, the recovery key (24 words + QR code), the configured backup locations (without passwords), and short restore instructions. The user is asked to print it or store it offline, and must confirm 3 random words before continuing.
