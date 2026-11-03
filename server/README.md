# mvault: Mobile Vault backup server

A small **append-only** HTTPS backup API for the Mobile Vault Android app. It runs behind nginx on the owner's Ubuntu server.
It uses only the Python 3.10+ standard library plus the `openssl` CLI. No pip packages.

- The server only ever stores **already-encrypted** PVAULT2 files. It checks their structure but never decrypts them.
- **No delete, no overwrite.** Each revision is stored once (`link()` on a new name). Old revisions are pruned only by the retention timer.
- **Two factors per phone:** an mTLS client certificate (private CA; the key is created in the phone's Android Keystore) **and** a bearer token bound to that certificate.
- Pairing uses a QR code from `mvault-admin pair <name>`: single use, 10 minutes, attempt-limited. The QR also carries the server's SPKI pins.
- A lost phone is blocked with `mvault-admin revoke <name>`.
- Hourly mirror to a second disk (root-owned, so the service user can't touch it). Daily retention: the last 30 revisions plus the newest one per week for 52 weeks.

## API (prefix `/api/v1`)
| Method | Path | Auth | |
|---|---|---|---|
| `GET` | `/health` | none | `{"ok": true, "version": "1"}` |
| `POST` | `/pair` | one-time code | `{code, csr_pem}` → `{device, cert_pem, ca_pem, token}` |
| `GET` | `/vaults` | cert + token | `{"vaults": [{vault_id, latest_rev, latest_time, size, device, revisions}]}`, newest first, all vaults (single owner) |
| `PUT` | `/vaults/{id}/revs/{rev}` | cert + token | store a PVAULT2 file; 201, or 409 if the rev exists |
| `GET` | `/vaults/{id}/revs` | cert + token | `{vault_id, revs: [{rev, size, sha256, received, device}]}` |
| `GET` | `/vaults/{id}/revs/{rev}` | cert + token | the file, with `X-Content-SHA256` |
| `GET` | `/vaults/{id}/latest` | cert + token | metadata of the highest rev |

There is no `DELETE` (405). Full description: `docs/BACKUP_AND_RECOVERY.md`. Installation: `docs/CLAUDE_CODE_TASK_03.md`.

## Pair a new phone
1. On the owner's PC, double-click **`tools/windows/Pair phone.cmd`** (first time: create `%USERPROFILE%\mobilevault-secrets\pair-phone.json` from `tools/windows/pair-phone.example.json`; it holds only the SSH login, no password).
2. Type a name for the phone (Enter = `phone-YYYYMMDD`). The script runs `mvault-admin pair <name>` over SSH and shows the QR code full screen with a 10-minute countdown and the paste text. The QR is made on the server by `qrencode` and streamed back as text; nothing is stored on the server or on the PC.
3. On the phone: Settings → Backup → Connect → **Scan QR code**, then **Test connection**.
4. Replacing a phone, or one was lost: `mvault-admin revoke <old-name>` on the server.

Fallback: SSH to the server, `mvault-admin pair <name>`, paste the `Payload:` line into the app ("Or paste the pairing text").

## Layout
| Path | |
|---|---|
| `mvault/common.py` | config, PVAULT2 header check, atomic append-only storage |
| `mvault/server.py` | HTTP service (loopback only) |
| `mvault/admin.py` | `init-ca`, `pair`, `list` (incl. each client certificate's expiry), `revoke`, `retention`, `stats` |
| `tests/test_mvault.py` | unit/integration tests: `cd server && python3 -m unittest -v` |
| `tools/mvault_smoke.py` | end-to-end test through nginx/TLS, acting as a phone |
| `deploy/` | systemd units, nginx site + rate limit, fail2ban filter/jail, config example (placeholders `<DOMAIN>`, `<DATA_DISK>`, `<MIRROR_DISK>`, `<LAN_SUBNET>`) |

Verified on nginx 1.24 / OpenSSL 3.0.13 / Python 3.13: unit tests plus the full smoke test through nginx with mTLS. Also checked: faked `X-Client-*` headers without a certificate get 403, other paths get 404, and the pairing rate limit works.
