# Task 02 for Claude Code: harden the Ubuntu server (approved by the owner)

Context: `docs/SERVER_CHECK.md`. The owner relaying this task **is the approval** for the changes in Parts B–D below. Do **not** do anything outside this list. For Part E, only report.
The owner says SSH is currently **not** port-forwarded from the internet. Phone backups will use **HTTPS through nginx** (not SFTP); SSH stays LAN-only.

## Part A: Commit pending doc changes
Commit the already-modified docs in `C:\Users\spirt\mobilevault` (DECISIONS, ANDROID_APP_SPEC, BACKUP_AND_RECOVERY, this task file) with the message `Docs: choose SFTP, add server hardening task`, then push.

## Lock-out safeguards (mandatory for B and C)
1. Before touching sshd: verify that a **key** login works for the admin user (`<ADMIN_USER>`) and that this user has `sudo`. Verify the key you use yourself as well.
2. Keep your current SSH session open the whole time. After each change, test with a **new** session before you close anything.
3. Back up every file before editing it (`cp -a file file.bak-2026-10-29`).
4. Run `sshd -t` before every reload. Use `systemctl reload ssh`, not restart.
5. If anything fails, roll back immediately and report.

## Part B: SSH
- sshd uses the **first** value it reads for each option. Create `/etc/ssh/sshd_config.d/00-hardening.conf` (it sorts before `50-cloud-init.conf`) containing:
  ```
  PermitRootLogin prohibit-password
  PasswordAuthentication no
  KbdInteractiveAuthentication no
  PubkeyAuthentication yes
  MaxAuthTries 3
  LoginGraceTime 30
  X11Forwarding no
  ```
  (`prohibit-password` keeps root key login working for Claude Code. Later we may switch to `<ADMIN_USER>` + sudo and set `PermitRootLogin no`.)
- Verify with `sshd -T | grep -Ei 'permitrootlogin|passwordauthentication|kbdinteractive'`, and from a new session check that a password login is refused.

## Part C: Firewall (ufw)
1. First list exactly what each service needs, and **show the rule list to the owner in chat before enabling**: turnserver ports + relay range (`min-port`/`max-port` in its config), what the Node apps on 3000/3001/8083 are and whether nginx proxies them, and whether Docker is installed (Docker bypasses ufw, so report it if present).
2. Planned rules (adjust to the findings):
   - default deny incoming, allow outgoing
   - allow 22/tcp **from <LAN_SUBNET> only**
   - allow 80/tcp, 443/tcp (nginx, public)
   - allow 139,445/tcp + 137,138/udp **from <LAN_SUBNET> only** (Samba)
   - TURN: 3478 tcp/udp + relay range as configured
   - Node apps 3000/3001/8083: LAN only, unless they must be public (ask)
3. Add the SSH allow rule **before** `ufw enable`. After enabling, test a new SSH session plus `curl -I https://<DOMAIN>` from the server.

## Part D: fail2ban
`apt install fail2ban`, then add `/etc/fail2ban/jail.d/sshd.local` with the `[sshd]` jail enabled, `maxretry = 5`, `bantime = 1h`, and `ignoreip = 127.0.0.1/8 <LAN_SUBNET>`. Enable the service, then check `fail2ban-client status sshd`.

## Part E: Report only (no changes)
1. The owner believes the public IP is the one the domain resolves to. Confirm with `curl -s https://ifconfig.me`. Report whether the TURN `external-ip` is stale (the other app's TURN relay may be broken because of it), but don't change it.
2. Does nginx actually serve the Samba `soft` folder (autoindex? which location)? What exactly is publicly downloadable?
3. Free space on both data disks, and which disk you'd put `/srv/mobilevault` on (the vault archive will be tiny; we'll mirror it to the second disk).
4. Proposals for backing up the server itself (e.g. restic to the second disk + an off-site copy).
5. Preparation for the HTTPS backup API (read-only): list the nginx server blocks and server_names; how certbot validates (http-01 / dns-01); where DNS for the domain is managed and whether a `vault.` subdomain A-record can be added; is Python ≥ 3.10 installed; does the router do NAT loopback (can the server/LAN reach `https://<DOMAIN>` by its public name).

## Output
Update `docs/SERVER_CHECK.md` with a "Hardening done 2026-10-29" section (placeholders only, the repo is public), commit, push, and report to the owner, including anything you did NOT do and why.
