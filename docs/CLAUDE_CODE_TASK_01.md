# Task 01 for Claude Code: initial push + read-only server check

You are helping build **Mobile Vault**, an Android password manager (see `README.md` and `docs/`).
You have access to GitHub (`spirtosan/mobilevault`) and to the owner's Ubuntu server.

## Part A: Push the project to GitHub
1. The project folder is `C:\Users\spirt\mobilevault` (on the owner's Windows PC). The GitHub repo `https://github.com/spirtosan/mobilevault` exists and is empty.
2. In that folder: `git init -b main`, check that `.gitignore` is present, `git add -A`, review `git status` (there must be **no** `*.vault`, `*.pvault`, keys or passwords; `test-vectors/sample-pvault1.bin` is a deliberate test file with a public test password and is OK), commit `Initial project docs, desktop reference and test vectors`, add remote `origin`, push `main`.
3. If the folder isn't reachable from where you run, stop and tell the owner instead of recreating files.

## Part B: Read-only check of the Ubuntu server
**Do not install, change, restart or open anything.** Only read. Use `sudo` only for read-only commands where needed.
Collect:
1. OS: `lsb_release -a`, `uname -r`, uptime, pending security updates (`apt list --upgradable 2>/dev/null | wc -l`), unattended-upgrades enabled?
2. Resources: CPU/RAM (`nproc`, `free -h`), disks and free space (`df -hT`), filesystem types (is any btrfs/zfs/LVM thin, i.e. snapshot-capable?).
3. SSH: `sshd -T | grep -Ei '^(port|passwordauthentication|permitrootlogin|pubkeyauthentication|kbdinteractiveauthentication|allowusers|allowgroups|subsystem)'`, is `internal-sftp` usable, fail2ban/crowdsec present and active?
4. Firewall: `ufw status verbose` (or `nft list ruleset | head -50` / `iptables -S | head -50`), listening ports `ss -tlnp`.
5. Web: nginx/apache/caddy installed or running? versions; any existing WebDAV or Nextcloud? certbot installed, existing certificates (`certbot certificates`, names and expiry only).
6. Reachability: does the server have a public IP or domain name? Is it behind NAT/router port-forwarding? Is it reachable from the internet on 22 and/or 443? (Ask the owner if you can't tell; don't scan from outside without permission.)
7. Existing backups of the server itself (cron jobs, systemd timers, restic/borg/rsync, snapshots).
8. Time sync (`timedatectl`).

## Output
Write `docs/SERVER_CHECK.md` with:
- a summary table of the findings,
- a recommendation: **SFTP** (chrooted key-only user) vs **WebDAV over HTTPS** for the phone backups (see `docs/BACKUP_AND_RECOVERY.md`), with reasons,
- a list of the changes that *would* be needed (for later approval; not done now),
- any security issues noticed on the server.

**Privacy:** If the GitHub repo is public, do NOT put real IP addresses, hostnames, domain names, usernames or open-port details in the committed file. Replace them with placeholders (`<SERVER_HOST>`, `<ADMIN_USER>`) and give the real values only to the owner in chat. Commit + push `docs/SERVER_CHECK.md`, then report back to the owner.
