# Server check (read-only), 2026-10-29

This was a read-only inspection of the owner's Ubuntu server, done to choose the backup protocol for Mobile Vault (see `BACKUP_AND_RECOVERY.md`). Nothing was installed, changed or restarted.

**Privacy:** this repo is public, so real hostnames, IPs, domain names, usernames, paths that contain names, and port numbers are replaced with placeholders: `<SERVER_HOST>`, `<DOMAIN>`, `<ADMIN_USER>`, `<DATA_DISK>`, `<SSH_EXT_PORT>`. The owner received the real values separately.

## Summary of findings

| Area | Finding | Status |
|---|---|---|
| OS | Ubuntu 24.04.5 LTS, kernel 6.8.0-147-generic, up 3 days | ✅ |
| Updates | 3 upgradable packages, 0 of them security updates; no reboot required | ✅ |
| Unattended upgrades | Installed, enabled, active (daily list update + unattended-upgrade) | ✅ |
| CPU / RAM | 4 threads (Intel i3-7101TE), 11 GiB RAM (~10 GiB available), 4 GiB swap unused | ✅ plenty |
| Disks | NVMe 238 GB system disk (7% used), SSD 224 GB data disk (3% used), HDD 1.8 TB data disk (42% used) | ✅ plenty of space |
| Filesystems | All **ext4**, no LVM, no btrfs/zfs in use (btrfs-progs installed but unused) | ⚠️ no snapshots possible |
| SSH server | OpenSSH 9.6p1, listens on all interfaces | — |
| SSH: pubkey auth | yes | ✅ |
| SSH: **password auth** | **yes** (set explicitly in `sshd_config.d/50-cloud-init.conf`) | ❌ |
| SSH: **root login** | **yes** (passwords allowed too) | ❌ |
| SSH: kbd-interactive | no | ✅ |
| SSH: AllowUsers/AllowGroups | not set | ⚠️ |
| SSH: X11Forwarding | yes | ⚠️ |
| SSH: SFTP subsystem | external `sftp-server`; `internal-sftp` is available (OpenSSH 9.6) and works for a `Match` + `ChrootDirectory` setup | ✅ |
| fail2ban / CrowdSec | **not installed** | ❌ |
| Host firewall | **ufw inactive**, nftables tables empty, iptables policy ACCEPT everywhere | ❌ |
| Listening services | SSH, nginx (HTTP + HTTPS), Samba (SMB/NetBIOS), coturn (TURN), several Node.js apps (one under pm2), local DNS stub. All of them except coturn and DNS listen on **all interfaces** | ⚠️ |
| Web server | nginx 1.24.0, active; one site (`<DOMAIN>`) serving static files and reverse-proxying the Node apps. Apache and Caddy are not installed | ✅ |
| WebDAV | nginx has `http_dav_module` compiled in, but it is not configured anywhere. The `dav-ext` module (PROPFIND, needed for directory listing) is **not** installed. No Nextcloud | — |
| TLS | certbot 2.9.0 + nginx plugin; one Let's Encrypt cert for `<DOMAIN>` (+ www), expires 2026-12-17 (48 days); `certbot.timer` is active, so it renews automatically | ✅ |
| Network | Private LAN address behind the home router (NAT). A public domain name exists and resolves to a public IP | — |
| Reachability | HTTPS is evidently published through the domain. SSH looks port-forwarded on a non-standard external port (`<SSH_EXT_PORT>`; this PC's `known_hosts` has an entry for it). **Not verified from outside** (no external scan was done) | ❓ owner to confirm |
| Backups of the server | **None found.** No restic/borg/duplicity/timeshift/snapper. Only stock cron/timers (certbot, logrotate, dpkg-db-backup). rsync is installed but nothing schedules it | ❌ |
| Time sync | systemd-timesyncd active, clock synchronised, UTC | ✅ |
| Local users | 1 regular user (`<ADMIN_USER>`, in `sudo`); root has 1 authorized key | — |

## Recommendation: **SFTP** (chrooted, key-only user)

> **Superseded 2026-10-29:** the owner chose **HTTPS through the existing nginx** instead (see `DECISIONS.md` and `BACKUP_AND_RECOVERY.md`). SSH stays LAN-only. The SFTP-specific steps below (sections 2 and the SFTP parts of 1) will not be done. The hardening that was actually done is in [Hardening done 2026-10-29](#hardening-done-2026-10-29).

Reasons:

1. **Stronger auth with no extra effort.** The phone generates an Ed25519 key that is wrapped by the Android Keystore. Nothing like a password exists that could be phished, reused or guessed. WebDAV through nginx would rely on HTTP Basic auth (a static password in an htpasswd file), and nginx has no per-user write restrictions beyond that.
2. **No new internet-facing code.** OpenSSH is already running and is the best-audited daemon on the box. WebDAV would add a new nginx location to a vhost that already proxies several in-house Node apps, and full listing support would need an extra module (`libnginx-mod-http-dav-ext`). That module would then have to stay patched as well.
3. **The append-only design maps directly onto Unix permissions.** It works like this: a chroot that root owns; `incoming/` writable by the backup user; `archive/` owned by root and only readable by the backup user; a root systemd timer that verifies files and moves them. With nginx WebDAV, every file would be owned by `www-data`. Keeping the phone from rewriting `archive/` would then depend on nginx `limit_except` rules rather than kernel-enforced permissions.
4. **Host pinning is simpler.** Pinning one SSH host key fingerprint is stable. TLS pinning of a Let's Encrypt cert breaks every 60–90 days unless the app pins the key or the CA.
5. **Network concern is small.** The concern is that some public Wi-Fi blocks non-443 ports. But uploads are background `WorkManager` jobs with retry, so a blocked network only delays a backup until the next network. The app already warns after 7 days without a successful backup.

Trade-offs to accept:

- SSH must stay reachable from the internet. It seemingly already is, but in an **unsafe configuration** (see below). Hardening SSH is a prerequisite for SFTP, and it should be done regardless of the protocol choice.
- The Android client uses **sshj** instead of OkHttp. `ANDROID_APP_SPEC.md` still mentions "OkHttp (WebDAV / S3)" and `backup/ … WebDAV` and should be updated once this recommendation is approved.

WebDAV stays a valid fallback. The `BackupTarget` interface is pluggable, so a WebDAV target can be added later if SSH has to be closed to the internet.

## Changes that *would* be needed (for later approval; **none done**)

Order matters: harden first, then expose the backup account.

### 1. SSH hardening
- [ ] Make sure the owner's key login as `<ADMIN_USER>` works and `sudo` works, **before** touching auth settings. Keep an open root session while testing.
- [ ] Edit `/etc/ssh/sshd_config.d/50-cloud-init.conf`: set `PasswordAuthentication no` (or remove the line). sshd uses the **first** value it reads, and drop-ins are read in name order, so a new file sorted after `50-…` will **not** override it. Either edit it, or put the new settings in a file that sorts earlier (e.g. `10-…`).
- [ ] New drop-in (e.g. `10-hardening.conf`): `PermitRootLogin no` (or `prohibit-password` as an interim step), `X11Forwarding no`, `MaxAuthTries 3`, `AllowGroups sudo sftp-backup`.
- [ ] `sshd -t`, then `systemctl reload ssh`; test a new login before closing the old session.

### 2. SFTP backup account
- [ ] Group `sftp-backup`; user `<BACKUP_USER>` with shell `/usr/sbin/nologin` and no password.
- [ ] Layout on `<DATA_DISK>` (e.g. `/srv/mobilevault/<BACKUP_USER>/`):
  - chroot dir: `root:root 755`
  - `incoming/`: `<BACKUP_USER>:<BACKUP_USER> 700` (phone writes here)
  - `archive/`: `root:sftp-backup 750` (phone can read/list, cannot write/delete)
  - `.ssh/authorized_keys` outside the chroot, e.g. `/etc/ssh/authorized_keys/<BACKUP_USER>`, root-owned
- [ ] sshd `Match Group sftp-backup` → `ChrootDirectory /srv/mobilevault/%u`, `ForceCommand internal-sftp -u 0077`, `AllowTcpForwarding no`, `AllowAgentForwarding no`, `PermitTunnel no`, `X11Forwarding no`, `PasswordAuthentication no`.
- [ ] Authorized key options: `restrict` (plus optionally a `from=` limit; usually impractical on mobile).
- [ ] Root systemd service + timer (e.g. every 5 min) running an archiver script. The script checks the `PVAULT2` magic and that the sidecar `.sha256` matches, moves valid files into `archive/`, quarantines bad ones, and applies retention (last 30 revisions + 1/week for 1 year).
- [ ] Record the server's Ed25519 host-key fingerprint for pinning in the app.

### 3. Firewall
- [ ] ufw: `default deny incoming`, `default allow outgoing`; allow SSH and 80/443 from anywhere. Allow Samba, TURN and the directly exposed Node app ports **from the LAN subnet only** (or bind those apps to localhost, since nginx already proxies them). Enable ufw only after the SSH rule is in place.
- [ ] Router: confirm that only the HTTPS/HTTP ports and the SSH port are forwarded. SMB in particular must not be.

### 4. Brute-force protection
- [ ] Install fail2ban with the `sshd` jail (or CrowdSec).

### 5. Backups of the server itself
- [ ] At minimum, back up `/srv/mobilevault/*/archive/` (plus `/etc` and the web/app data) **off-site**, e.g. with restic to a second location (another machine, a USB disk kept elsewhere, or a cloud bucket; the data is already encrypted). Without this, the server becomes the single point of failure.
- [ ] Optional: copy to the other local data disk too. This is not off-site, but it protects against a single disk failure.
- [ ] Snapshots aren't available on ext4 without LVM. Adding them would require repartitioning, so restic versioning is the practical substitute.

### 6. Housekeeping
- [ ] Apply the 3 pending (non-security) updates at a convenient time.
- [ ] Update `ANDROID_APP_SPEC.md` (network/backup lines) to reflect SFTP/sshj.

## Security issues noticed

1. **Critical: root login with password allowed, and SSH appears to be internet-reachable.** `PermitRootLogin yes` + `PasswordAuthentication yes`, with no firewall and no fail2ban. The server is exposed to continuous password-guessing against root. Fixing this (section 1 + 4) is the top priority, independent of Mobile Vault.
2. **High: no host firewall.** Every service that binds to all interfaces (SMB, the Node apps, SSH, nginx) is reachable wherever the router lets traffic through. The Node apps can also be reached directly, bypassing nginx.
3. **High: no backups of the server.** This includes the data disks and the web/app content.
4. **Medium: an SMB-writable share sits inside the public web root.** Anything dropped into that share via SMB is served publicly by nginx. Check that this is intended, and make sure no sensitive files end up there.
5. **Low: `X11Forwarding yes`, no `AllowUsers/AllowGroups`, `MaxAuthTries 6`.**
6. **Info: the TURN server's configured public IP does not match the IP the domain currently resolves to.** Either the WAN IP changed or the DNS points elsewhere. Details went to the owner directly.
7. **OK:** unattended security upgrades are on, the TLS cert auto-renews, time is synchronised, and no security updates are pending.

---

## Hardening done 2026-10-29

These changes were approved by the owner (Task 02 plus their follow-up answers) and done with lock-out safeguards. Those safeguards were:
- a standby root session kept open the whole time;
- every file backed up first (into `/root/config-backups-2026-10-29/` and `*.bak-2026-10-29`);
- `sshd -t` / `nginx -t` before every reload;
- a 5-minute automatic-rollback `systemd-run` timer for the sshd and ufw changes, cancelled only after new sessions tested OK.

### 1. nginx: stopped publishing secrets (urgent fix)
**Problem found:** the public web root is a whole data folder, and `location /` has `autoindex on`. Because of that, the source folder of a secondary copy of an internal web app was downloadable from the internet. That included its `.env` (session secret), its SQLite database (user accounts with bcrypt password hashes), its `server.js` and `setup.js`. A folder of app pages was also public.

**Fix:** added to the HTTPS server block, before `location /`:
```nginx
location ^~ /.well-known/acme-challenge/ { allow all; }
location ~ /\. { deny all; }
location ~* \.(sqlite|sqlite3|db|env|bak|old|orig|swp|log|key|pem|vault|pvault|diary)$ { deny all; }
location ^~ /<APP_COPY_DIR> { deny all; }
location ^~ /<NOTES_DIR>    { deny all; }
```
**Verified:**
- `.env`, `database.sqlite`, `server.js`, both folders, `/.git/config` and any `*.env`: **403**.
- `/`, the downloads folder and the proxied app: **200**, unchanged. The other proxied app gives its usual 302.
- The ACME path is not blocked (404 with no token).

**Access-log review:** the logs, including rotated `.gz` files, cover **15–29 Oct 2026 only**. In that window:
- **No non-LAN request** fetched the `.env`, the database or the source files.
- Hundreds of automated scanner requests for generic paths like `/.env`, `/.git/config` and `/.env.production` all got 404.
- Hits on the notes folder came only from Google crawlers, on a privacy-policy page.

The files were exposed for months before the logs start, so a leak before 15 Oct **can't be ruled out**. Rotating the secret and passwords is recommended (owner to do).

### 2. SSH: key-only
- The owner's PC key was added for `<ADMIN_USER>`; key login and `sudo` membership were verified in a new session.
- New `/etc/ssh/sshd_config.d/00-hardening.conf` (it sorts before `50-cloud-init.conf`; the first value wins):
  `PermitRootLogin prohibit-password`, `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PubkeyAuthentication yes`, `MaxAuthTries 3`, `LoginGraceTime 30`, `X11Forwarding no`.
- Verified: `sshd -T` shows the values. New key sessions for root and `<ADMIN_USER>` work. Password logins for both are refused (`Permission denied (publickey)`).
- SSH is **not** port-forwarded on the router (owner), and is now LAN-only at the firewall too.

### 3. Firewall (ufw): enabled
- Default deny incoming / allow outgoing; routed traffic disabled.
- **LAN only (`<LAN_SUBNET>`):** SSH, Samba (TCP 139/445, UDP 137/138), and the internal Node.js apps.
- **Anywhere:** HTTP/HTTPS (nginx), and the TURN listener plus its relay UDP range as set in its config.
- Some older, never-enabled rules were already in the ufw config: nginx profile, TURN, and a smaller relay range. They duplicate or fall inside the rules above and were left in place.
- Docker is not installed, so nothing bypasses ufw.
- Verified after enabling: new SSH sessions work; HTTPS by public name works (NAT loopback OK); the proxied apps answer; Samba and app ports are still reachable from the LAN.

### 4. fail2ban
- Installed (Ubuntu package 1.0.2-3ubuntu0.1). `/etc/fail2ban/jail.d/sshd.local`: `[sshd]` enabled, `maxretry = 5`, `bantime = 1h`, `ignoreip = 127.0.0.1/8 <LAN_SUBNET>`.
- **Extra line needed:** `journalmatch = _SYSTEMD_UNIT=ssh.service + _COMM=sshd`. On Ubuntu 24.04 the unit is `ssh.service`; the stock filter watches `sshd.service` and would never match anything. With the fix, a `fail2ban-regex` test over the journal matches past failed logins.
- Service active; `fail2ban-client status sshd` OK.

### 5. fstab: UUIDs + `nofail`
- Both data disks are now mounted by `UUID=` with `defaults,nofail`, so a renamed or missing disk can't mount in the wrong place or hang the boot. The backup is `/etc/fstab.bak-2026-10-29`.
- `findmnt --verify`: no errors (one harmless warning about the swap file). `mount -a` OK, no reboot.

### Report-only findings (Part E)
| Item | Finding |
|---|---|
| Public IP | The current public IP matches the domain's DNS record |
| TURN `external-ip` | **Stale**: it contains an old public IP, so TURN relaying for clients outside the LAN is probably broken. Not changed (owner: later) |
| Data disks | `<DATA_DISK_A>` (1.8 TB HDD): ~1.0 TB free. `<DATA_DISK_B>` (224 GB SSD): ~204 GB free |
| Where to put `/srv/mobilevault` | Primary on `<DATA_DISK_A>`, hourly mirror to `<DATA_DISK_B>` (two physical disks) |
| nginx | One site (`<DOMAIN>` + `www`): port 80 redirects to HTTPS, 443 serves static files + reverse proxies. A `vault.` subdomain will need its own server block |
| certbot | nginx authenticator, i.e. **http-01**; ECDSA key; auto-renew timer active. Works for `vault.<DOMAIN>` if port 80 stays forwarded |
| DNS | Hosted at a third-party DNS provider; a `vault.` A record has to be added in its panel (owner) |
| Python | 3.12.3 (≥ 3.10 ✅) |
| NAT loopback | **Works**: the server and LAN reach `https://<DOMAIN>` by its public name |
| Server backups | Still none. Planned as a separate task (restic to the second disk + off-site) |

### Not done (and why)
- **Rotating the internal app's session secret and user passwords:** the owner asked for instructions only.
- **TURN `external-ip`, restic server backup:** deferred by the owner.
- **`PermitRootLogin no`:** root key login is kept for now (Task 02). Switch later once all admin work goes through `<ADMIN_USER>` + sudo.
- **Pending package updates (3, non-security):** not in scope.

### Still open (recommended)
- The live internal app's `server.js` contains a **hard-coded session secret and hard-coded seed admin passwords**. Move them to an environment file outside the web root, and change any account that still uses a seed password.
- The app copy's `.env` is world-readable on disk (`644`); it should be `600`.
- The `<NOTES_DIR>` privacy page is now 403. If it is linked from an app store listing, re-allow just that page.

---

## Follow-up fixes 2026-10-29
- **nginx:** the notes folder's privacy-policy page is public again (`autoindex off`; Google fetches it daily). The rest of the folder is still 403.
- **Internal app secrets rotated:**
  - The app copy's `.env` got a new random `SESSION_SECRET` and is now `600`.
  - The live app's hard-coded session secret was replaced with `process.env.SESSION_SECRET`, set via pm2 and saved with `pm2 save`.
  - The hard-coded seed admin passwords were replaced with `TRACKER_SEED_PW_1/2` env variables. Seeding is skipped when they are unset; existing users are never changed.
  - Both apps were restarted and answer normally. User passwords will be changed by the owner.

## Backup server deployed 2026-10-29
The mvault append-only HTTPS backup API (`server/`) is live at `https://vault.<DOMAIN>/api/v1`.

| Item | State |
|---|---|
| DNS | `vault.<DOMAIN>` A record → `<PUBLIC_IP>` ✅ |
| Unit tests (server, Python 3.12) | 9/9 OK. They need `server/tests/__init__.py` (added) so that `python3 -m unittest -v` discovers them |
| Service user | `mvault` (system user, no home, no shell login) |
| Code | `/opt/mvault` (root-owned, read-only for the service); admin wrapper `/usr/local/sbin/mvault-admin` |
| Config | `/etc/mvault/config.json` (root:mvault 0640); `backup_pins` holds the spare-key pin |
| Client CA | `/etc/mvault/ca` (root:mvault 0750; `ca.key` 0440); EC P-256, valid until 2036 |
| TLS | Let's Encrypt ECDSA cert for `vault.<DOMAIN>`, webroot http-01, `--reuse-key`, deploy hook reloads nginx |
| Spare key (pin rotation) | Generated, pin stored in config, key copied to the owner's PC outside the repo, shredded on the server |
| nginx | Separate vault server block: mTLS `optional` (required for everything except `/health` and `/pair`), rate limits, 5 MB body limit, `/` → 404. The main site does **not** request client certificates |
| Service | `mvault.service` active, listens on **127.0.0.1:8790 only** (not reachable from the LAN). `systemd-analyze security`: **1.5 OK** |
| Timers | `mvault-retention.timer` (daily), `mvault-mirror.timer` (hourly) |
| Storage | `<DATA_DISK_A>/mobilevault/data` (mvault 0750) → mirror `<DATA_DISK_B>/mobilevault-mirror` (root:root, dirs 0750, files 0440; the service user cannot even list it) |
| fail2ban | `mvault` jail on the vault access log (maxretry 10 / 10 min, ban 1 h, LAN ignored) |
| Firewall | No change needed (80/443 already open) |

### Deviations from `server/deploy/` (fixed in the repo too)
1. **fail2ban filter never matched:** fail2ban removes the timestamp before matching, so `\[[^\]]+\]` became `\[[^\]]*\]`. Verified: 401/403 lines match, 200/409 lines don't.
2. **Mirror would not have been root-owned:** `rsync -a` running as root keeps the source owner (`mvault`). Added `--chown=root:root`.
3. **`http2` removed from the vault `listen` lines:** on nginx 1.24 it is a per-socket option and would also have switched the existing site's WebSocket apps to HTTP/2. The phone client works over HTTP/1.1.

### End-to-end smoke test (through the public name, TLS + mTLS)
```
PASS health without cert  (got 200, want 200)
PASS API without client cert is refused  (got 403, want 403)
PASS pairing  (got 201, want 201)
PASS upload rev 1  (got 201, want 201)
PASS overwrite refused  (got 409, want 409)
PASS delete refused  (got 405, want 405)
PASS list  (got (200, [1]), want (200, [1]))
PASS download matches  (got (200, True), want (200, True))
PASS wrong token refused  (got 401, want 401)
ALL PASSED
```
Extra checks:
- `https://vault.<DOMAIN>/` → 404.
- Faked `X-Client-Verify: SUCCESS` header without a certificate → 403.
- After `mvault-admin revoke smoketest`, the same certificate → 403 `device revoked`.
- The mirror run copied the test vault as root:root.
- The test vault was then removed from data **and** mirror. The revoked `smoketest` entry stays in the device list as a record.

### Notes
- The mirror never deletes, so it keeps revisions that retention prunes from the data disk. It's tiny, but it grows without limit.
- Off-site copy / restic server backup: still a separate, later task.
