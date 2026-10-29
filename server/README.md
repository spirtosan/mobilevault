# mvault: Mobile Vault backup server

A small **append-only** HTTPS backup API for the Mobile Vault Android app. It runs behind nginx on the owner's Ubuntu server.
It uses only the Python 3.10+ standard library plus the `openssl` CLI. No pip packages.

- The server only ever stores **already-encrypted** PVAULT2 files. It checks their structure but never decrypts them.
- **No delete, no overwrite.** Each revision is stored once (`link()` on a new name). Old revisions are pruned only by the retention timer.
- **Two factors per phone:** an mTLS client certificate (private CA; the key is created in the phone's Android Keystore) **and** a bearer token bound to that certificate.
- Pairing uses a QR code from `mvault-admin pair <name>`: single use, 10 minutes, attempt-limited. The QR also carries the server's SPKI pins.
- A lost phone is blocked with `mvault-admin revoke <name>`.
- Hourly mirror to a second disk (root-owned, so the service user can't touch it). Daily retention: the last 30 revisions plus the newest one per week for 52 weeks.

API reference: `docs/BACKUP_AND_RECOVERY.md`. Installation: `docs/CLAUDE_CODE_TASK_03.md`.

## Layout
| Path | |
|---|---|
| `mvault/common.py` | config, PVAULT2 header check, atomic append-only storage |
| `mvault/server.py` | HTTP service (loopback only) |
| `mvault/admin.py` | `init-ca`, `pair`, `list`, `revoke`, `retention`, `stats` |
| `tests/test_mvault.py` | unit/integration tests: `cd server && python3 -m unittest -v` |
| `tools/mvault_smoke.py` | end-to-end test through nginx/TLS, acting as a phone |
| `deploy/` | systemd units, nginx site + rate limit, fail2ban filter/jail, config example (placeholders `<DOMAIN>`, `<DATA_DISK>`, `<MIRROR_DISK>`, `<LAN_SUBNET>`) |

Verified on nginx 1.24 / OpenSSL 3.0.13 / Python 3.13: unit tests plus the full smoke test through nginx with mTLS. Also checked: faked `X-Client-*` headers without a certificate get 403, other paths get 404, and the pairing rate limit works.
