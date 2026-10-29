# Task 03 for Claude Code: deploy the mvault backup server

Code: `server/` (see `server/README.md`). Design: `docs/BACKUP_AND_RECOVERY.md`.
The owner relaying this task **approves** the steps below. Use the same safeguards as Task 02 (back up every file you edit, `nginx -t` before each reload, roll back on failure). **Placeholders only** in anything committed.

Values: `<DOMAIN>` = the owner's domain, `<DATA_DISK>` = the large HDD mount, `<MIRROR_DISK>` = the SSD mount, `<LAN_SUBNET>` = the LAN /24 (same values as SERVER_CHECK.md).

## 0. Preconditions (stop if they fail)
- `dig +short vault.<DOMAIN>` returns the public IP. The owner must add the A record at the DNS provider first.
- On the server, as a normal user, run the tests: copy `server/` to a temp dir, then `python3 -m unittest -v` (all must pass).

## 1. User, directories, code
```
adduser --system --group --no-create-home --home /nonexistent mvault
install -d -o root   -g root   -m 0755 /opt/mvault
cp -r server/mvault /opt/mvault/ && chown -R root:root /opt/mvault && chmod -R u=rwX,go=rX /opt/mvault
install -d -o root   -g mvault -m 0750 /etc/mvault
install -d -o mvault -g mvault -m 0750 /var/lib/mvault
install -d -o root   -g root   -m 0755 /mnt/<DATA_DISK>/mobilevault
install -d -o mvault -g mvault -m 0750 /mnt/<DATA_DISK>/mobilevault/data
install -d -o root   -g root   -m 0700 /mnt/<MIRROR_DISK>/mobilevault-mirror
```
Wrapper `/usr/local/sbin/mvault-admin` (0755):
```sh
#!/bin/sh
exec env PYTHONPATH=/opt/mvault MVAULT_CONFIG=/etc/mvault/config.json /usr/bin/python3 -m mvault.admin "$@"
```

## 2. Config + client CA
- `/etc/mvault/config.json` from `server/deploy/config.json.example` (root:mvault 0640). Leave `backup_pins` empty for now.
- `mvault-admin init-ca`, then `chown root:mvault /etc/mvault/ca /etc/mvault/ca/ca.key && chmod 0750 /etc/mvault/ca && chmod 0440 /etc/mvault/ca/ca.key`.

## 3. TLS certificate for vault.<DOMAIN>
1. Temporarily install only the port-80 `server {}` block from `server/deploy/nginx-vault.conf` (make sure the webroot exists), `nginx -t`, reload.
2. `certbot certonly --webroot -w /var/www/html -d vault.<DOMAIN> --key-type ecdsa --reuse-key --deploy-hook "systemctl reload nginx"`
   (`--reuse-key` keeps the public key stable across renewals, so the phone's pin stays valid.)
3. **Spare key for pin rotation:** `openssl ecparam -name prime256v1 -genkey -noout -out /root/vault-spare.key`. Compute its pin with
   `openssl pkey -in /root/vault-spare.key -pubout -outform DER | openssl dgst -sha256 -binary | base64` and put `"sha256/<that>"` into `backup_pins`.
   Copy the spare key to the owner's PC (`%USERPROFILE%\mobilevault-secrets\`, which is **outside** the repo), then `shred -u /root/vault-spare.key`. Tell the owner to keep that file offline (USB) as well.

## 4. nginx
- `server/deploy/nginx-mvault-ratelimit.conf` → `/etc/nginx/conf.d/mvault-ratelimit.conf`
- full `server/deploy/nginx-vault.conf` → `/etc/nginx/sites-available/vault.<DOMAIN>` (replace placeholders), symlink into sites-enabled
- `nginx -t` && `systemctl reload nginx`. The existing site must still work (`curl -I https://<DOMAIN>/`, `/tracker/`).

## 5. systemd
Copy `mvault.service`, `mvault-retention.{service,timer}`, `mvault-mirror.{service,timer}` to `/etc/systemd/system/` (replace placeholders), then
`systemctl daemon-reload && systemctl enable --now mvault mvault-retention.timer mvault-mirror.timer`.
Check: `systemctl status mvault`, `ss -tlnp | grep 8790` (must be **127.0.0.1 only**), `systemd-analyze security mvault` (report the score).

## 6. fail2ban
`server/deploy/fail2ban-mvault.conf` → `/etc/fail2ban/filter.d/mvault.conf`, `fail2ban-mvault.local` → `/etc/fail2ban/jail.d/mvault.local` (`<LAN_SUBNET>`).
Test: `fail2ban-regex /var/log/nginx/mvault.access.log /etc/fail2ban/filter.d/mvault.conf`, reload fail2ban, `fail2ban-client status mvault`.

## 7. Firewall
Nothing new (443/80 are already open). Confirm port 8790 is not reachable from the LAN.

## 8. End-to-end test
```
sudo apt install -y qrencode     # optional, shows the QR for phone pairing
mvault-admin pair smoketest      # note the code
python3 server/tools/mvault_smoke.py https://vault.<DOMAIN>/api/v1 <CODE>    # from the owner's PC or the server
mvault-admin revoke smoketest
```
All checks must PASS. Then remove the test vault dir it prints (as root, from data **and** mirror). Also:
`curl -s -o /dev/null -w '%{http_code}' https://vault.<DOMAIN>/` → 404, and a request with a faked `X-Client-Verify: SUCCESS` header but no cert → 403.
Run `systemctl start mvault-mirror` once and check that the mirror is populated and root-owned.

## Output
Add a "Backup server deployed" section to `docs/SERVER_CHECK.md` (placeholders), commit `server/` + docs, push, and report the results, the `systemd-analyze security` score, and anything not done.
