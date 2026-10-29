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
    suspend fun delete(name: String)
}
```
Chosen target (see DECISIONS.md): **the owner's Ubuntu server only.** Google Drive was dropped.

Candidate protocols (final pick after `docs/SERVER_CHECK.md`):
| | **SFTP** (OpenSSH, already on Ubuntu) | **WebDAV over HTTPS** (nginx/Apache) |
|---|---|---|
| Server software | nothing new; a dedicated chrooted user, key-only login | web server + TLS cert (Let's Encrypt needs a domain) |
| Phone auth | Ed25519 key generated on the phone and kept in Android Keystore–wrapped storage | username + app password |
| Network | port 22 (sometimes blocked on public Wi-Fi) | port 443 (works almost everywhere) |
| Android library | sshj | OkHttp |
| Host verification | pin the SSH host key fingerprint | pin the TLS certificate SHA-256 |

Either way, the **server-side protection** is the same:
- The phone account can only write to `incoming/`. A root cron/systemd timer moves every new, verified file (magic header `PVAULT2`, SHA-256 matches the sidecar `.sha256`) into `archive/`, which the phone account **cannot read-modify-delete**. Even a stolen or compromised phone (or a bug) can't destroy the backup history.
- Retention in `archive/` is handled by the server: last 30 revisions plus 1 per week for 1 year.
- The phone reads restore points from a read-only `archive/` listing (or a `restore/` copy).
- Recommended: the server itself is also backed up (snapshot or off-site copy), because otherwise the server becomes the single point of failure.

The `BackupTarget` interface stays pluggable, so another server or a USB/SD copy (SAF) can be added later. Recommended minimum: the server, plus the paper recovery key, plus an occasional manual export.

## Upload logic
- Each save produces a new `rev`. A `WorkManager` job (network-connected constraint, exponential backoff) uploads `vault-<vault_id>-r<rev>-<utc>.pvault` and then overwrites `vault-<vault_id>-latest.pvault`.
- Integrity: after upload, compare SHA-256 with the server value (ETag, Content-MD5, or a re-download) before marking the upload successful.
- Retention on the remote: keep the last 30 revisions, plus 1 per week for 1 year. Old files are deleted only after a newer upload has been verified.
- Uploads don't need the vault to be unlocked, because the file is already encrypted.
- The **backup health** status is always visible, for example "Last remote backup: 2 h ago ✔". Warn after 7 days without a successful backup, and warn if there are unsynced changes.

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
