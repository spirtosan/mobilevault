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
