# Mobile Vault backup server: admin CLI (run as root)
# Copyright (C) 2026  github.com/spirtosan, GPL-3.0-or-later
"""Usage:
  mvault-admin init-ca                 create the private client CA (once)
  mvault-admin pair <device-name>      one-time pairing code + QR (valid 10 min)
  mvault-admin list                    paired devices
  mvault-admin revoke <device-name>    block a lost/stolen device immediately
  mvault-admin retention [--dry-run]   prune old revisions (systemd timer)
  mvault-admin stats                   vaults, revision counts, sizes, last device
  mvault-admin delete-vault <id> [--yes]           move a vault to the root-only trash (30 days)
  mvault-admin undelete-vault <id> [--entry NAME]  move the newest trash copy back
  mvault-admin trash                   trashed vaults, deletion date, purge date
  mvault-admin purge-trash [--dry-run] delete trash entries older than 30 days (retention timer)
"""

import argparse
import base64
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

from . import common as C

KEEP_LAST = 30
KEEP_WEEKS = 52
TRASH_DAYS = 30
TRASH_RE = re.compile(r"^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(\d{8}T\d{6}Z)$")
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # no 0/O/1/I


def _ca_paths(cfg):
    return os.path.join(cfg["ca_dir"], "ca.key"), os.path.join(cfg["ca_dir"], "ca.crt")


def cmd_init_ca(cfg, args):
    key, crt = _ca_paths(cfg)
    if os.path.exists(key) or os.path.exists(crt):
        sys.exit("CA already exists in %s, refusing to overwrite" % cfg["ca_dir"])
    os.makedirs(cfg["ca_dir"], mode=0o750, exist_ok=True)
    old = os.umask(0o077)
    try:
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key], check=True)
        subprocess.run(["openssl", "req", "-new", "-x509", "-key", key, "-out", crt, "-days", "3650",
                        "-sha256", "-subj", "/CN=Mobile Vault client CA",
                        "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
                        "-addext", "keyUsage=critical,keyCertSign,cRLSign"], check=True)
    finally:
        os.umask(old)
    os.chmod(crt, 0o644)
    print("CA created:", crt)
    print("Set ownership so the service can sign CSRs:  chown root:mvault %s && chmod 0440 %s" % (key, key))


def spki_pin(cert_path):
    """base64(SHA-256(SubjectPublicKeyInfo)), the format OkHttp's CertificatePinner uses.
    Works for a certificate or (with pubkey=True semantics) any PEM containing a public key."""
    pub = subprocess.run(["openssl", "x509", "-in", cert_path, "-noout", "-pubkey"],
                         capture_output=True, check=True).stdout
    der = subprocess.run(["openssl", "pkey", "-pubin", "-outform", "DER"],
                         input=pub, capture_output=True, check=True).stdout
    import hashlib
    return "sha256/" + base64.b64encode(hashlib.sha256(der).digest()).decode()


def cmd_pair(cfg, args):
    name = args.name
    if not C.NAME_RE.match(name):
        sys.exit("device name: letters, digits, . _ - (max 48)")
    devices = C.devices_store(cfg).load()
    if name in devices and not devices[name].get("revoked"):
        sys.exit("device '%s' already paired; revoke it first or choose another name" % name)
    code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(10))
    store = C.pairings_store(cfg)
    with store.lock:
        p = store.load()
        p[C.sha256_hex(code.encode())] = {"name": name, "attempts": 0,
                                          "expires_ts": time.time() + cfg["pair_ttl_seconds"]}
        store.save(p)
    payload = {"t": "mvault-pair", "v": 1, "url": cfg["public_url"], "code": code}
    pins = []
    if cfg.get("server_cert") and os.path.exists(cfg["server_cert"]):
        pins.append(spki_pin(cfg["server_cert"]))
    pins += [p for p in cfg.get("backup_pins", []) if p not in pins]
    if pins:
        payload["pins"] = pins
    else:
        print("WARNING: no server_cert/backup_pins configured – the phone will rely on public CAs only")
    text = json.dumps(payload, separators=(",", ":"))
    print("\nPair device '%s' – valid %d minutes, single use.\n" % (name, cfg["pair_ttl_seconds"] // 60))
    if shutil.which("qrencode"):
        subprocess.run(["qrencode", "-t", "ansiutf8", "-m", "2", text])
    else:
        print("(install 'qrencode' to show a QR code)")
    print("Code:", "-".join([code[:5], code[5:]]))
    print("Payload:", text)


def cmd_list(cfg, args):
    devices = C.devices_store(cfg).load()
    if not devices:
        print("no devices")
    for n, d in sorted(devices.items()):
        print("%-24s %-8s created %s  last seen %s  cert %s…  valid until %s" % (
            n, "REVOKED" if d.get("revoked") else "active", d.get("created"),
            d.get("last_seen", "-"), d.get("cert_sha256", "")[:16], C.device_cert_until(cfg, d)))


def cmd_revoke(cfg, args):
    store = C.devices_store(cfg)
    with store.lock:
        devices = store.load()
        if args.name not in devices:
            sys.exit("no such device")
        devices[args.name]["revoked"] = True
        devices[args.name]["revoked_at"] = C.now_iso()
        store.save(devices)
    print("revoked:", args.name)


def _parse_iso(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def plan_retention(revs, now=None):
    """Return the set of revs to delete. Keep the newest KEEP_LAST revisions, plus the newest
    revision of each ISO week within KEEP_WEEKS weeks. Never delete the latest."""
    now = now or datetime.now(timezone.utc)
    if not revs:
        return set()
    by_rev = sorted(revs, key=lambda m: m["rev"])
    keep = {m["rev"] for m in by_rev[-KEEP_LAST:]}
    weekly = {}
    for m in by_rev:
        t = _parse_iso(m["received"])
        if (now - t).days > KEEP_WEEKS * 7:
            continue
        wk = t.isocalendar()[:2]
        if wk not in weekly or m["rev"] > weekly[wk]:
            weekly[wk] = m["rev"]
    keep |= set(weekly.values())
    return {m["rev"] for m in by_rev} - keep


def cmd_retention(cfg, args):
    root = cfg["data_dir"]
    total = 0
    for vid in sorted(os.listdir(root)) if os.path.isdir(root) else []:
        if not C.UUID_RE.match(vid):
            continue
        revs = C.list_revisions(cfg, vid)
        doomed = plan_retention(revs)
        for rev in sorted(doomed):
            base = os.path.join(root, vid, C.rev_filename(rev))
            print(("would delete " if args.dry_run else "delete ") + base)
            if not args.dry_run:
                for p in (base, base + ".json"):
                    try:
                        os.unlink(p)
                    except FileNotFoundError:
                        pass
        total += len(doomed)
    print("retention: %d revision(s) %s" % (total, "to delete" if args.dry_run else "deleted"))


def cmd_stats(cfg, args):
    root = cfg["data_dir"]
    for vid in sorted(os.listdir(root)) if os.path.isdir(root) else []:
        if C.UUID_RE.match(vid):
            revs = C.list_revisions(cfg, vid)
            size = sum(m["size"] for m in revs)
            last = revs[-1] if revs else {}
            devs = sorted({str(m.get("device")) for m in revs})
            print("%s  %4d revs  %8d bytes  latest r%s at %s  last device %s  (all: %s)" % (
                vid, len(revs), size, last.get("rev"), last.get("received"), last.get("device"),
                ", ".join(devs) or "-"))
    n = len(_trash_entries(cfg))
    if n:
        print("trash: %d vault(s), see `mvault-admin trash`" % n)


# ── Trash: delete-vault / undelete-vault / trash / purge-trash (Task 11) ─────
# Only root on the server can do this; the API stays append-only (no DELETE).
def _utcnow():
    return datetime.now(timezone.utc)


def _stamp(dt):
    return dt.strftime("%Y%m%dT%H%M%SZ")


def _parse_stamp(s):
    return datetime.strptime(s, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)


def _iso(dt):
    return dt.isoformat().replace("+00:00", "Z")


def _trash_entries(cfg, vault_id=None):
    """[(name, vault_id, deleted_at)] in the trash, oldest first."""
    tdir = C.trash_dir(cfg)
    try:
        names = os.listdir(tdir)
    except FileNotFoundError:
        return []
    out = []
    for n in names:
        m = TRASH_RE.match(n)
        if m and os.path.isdir(os.path.join(tdir, n)) and vault_id in (None, m.group(1)):
            out.append((n, m.group(1), _parse_stamp(m.group(2))))
    out.sort(key=lambda e: (e[2], e[0]))
    return out


def _rev_metas(vdir):
    revs = sorted((int(m.group(1)), n) for n in os.listdir(vdir) for m in [C.REV_FILE_RE.match(n)] if m)
    return [C._rev_meta(vdir, n, r) for r, n in revs]


def _describe(vdir):
    """Revision count, newest rev + date, device of the last upload, all devices."""
    metas = _rev_metas(vdir)
    if not metas:
        return "  no revisions"
    last = metas[-1]
    return ("  revisions:   %d (%d bytes)\n  newest:      r%s at %s\n  last upload: from %s\n"
            "  all devices: %s") % (len(metas), sum(m.get("size") or 0 for m in metas), last["rev"],
                                    last.get("received"), last.get("device"),
                                    ", ".join(sorted({str(m.get("device")) for m in metas})))


def _vault_id_arg(v):
    v = v.strip().lower()
    if not C.UUID_RE.match(v):
        sys.exit("give the full vault ID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)")
    return v


def _move(src, dst):
    try:
        os.rename(src, dst)                 # same disk: atomic
    except OSError:
        if os.path.exists(dst):
            raise
        shutil.move(src, dst)


def cmd_delete_vault(cfg, args):
    vid = _vault_id_arg(args.vault_id)
    vdir = os.path.join(cfg["data_dir"], vid)
    if not os.path.isdir(vdir):
        sys.exit("no such vault on the server: %s" % vid)
    print("Vault %s\n%s" % (vid, _describe(vdir)))
    if not args.yes:
        print("\nThis moves the vault to the trash: phones can no longer list or restore it.\n"
              "`mvault-admin undelete-vault %s` brings it back within %d days." % (vid, TRASH_DAYS))
        try:
            answer = input("Type the first 8 characters of the vault ID to confirm: ")
        except EOFError:
            answer = ""
        if answer.strip().lower() != vid[:8]:
            print("Not confirmed: nothing changed.")
            sys.exit(1)
    tdir = C.trash_dir(cfg)
    os.makedirs(tdir, mode=0o700, exist_ok=True)
    os.chmod(tdir, 0o700)
    now = _utcnow()
    dest = os.path.join(tdir, "%s-%s" % (vid, _stamp(now)))
    if os.path.exists(dest):
        sys.exit("trash entry %s already exists; try again in a second" % dest)
    _move(vdir, dest)
    print("moved to the trash: %s\npermanently deleted after %s (retention timer)" % (
        dest, (now + timedelta(days=TRASH_DAYS)).date().isoformat()))


def cmd_undelete_vault(cfg, args):
    vid = _vault_id_arg(args.vault_id)
    entries = _trash_entries(cfg, vid)
    if args.entry:
        entries = [e for e in entries if e[0] == args.entry]
    if not entries:
        sys.exit("no trash copy of %s%s (see `mvault-admin trash`)" % (
            vid, " named " + args.entry if args.entry else ""))
    name = entries[-1][0]
    vdir = os.path.join(cfg["data_dir"], vid)
    if os.path.exists(vdir):
        print("Refused: vault %s exists again on the server (uploaded after it was deleted):" % vid)
        print(_describe(vdir) if os.path.isdir(vdir) else "  (not a directory)")
        print("Undeleting would mix two histories. Either keep the new one (the trash copy is purged\n"
              "after %d days), or move the new one to the trash first and name the old copy:\n"
              "  mvault-admin delete-vault %s\n  mvault-admin undelete-vault %s --entry %s" % (
                  TRASH_DAYS, vid, vid, name))
        sys.exit(1)
    _move(os.path.join(C.trash_dir(cfg), name), vdir)
    print("restored %s from the trash entry %s\n%s" % (vid, name, _describe(vdir)))


def cmd_trash(cfg, args):
    entries = _trash_entries(cfg)
    if not entries:
        print("trash is empty (%s)" % C.trash_dir(cfg))
    for name, vid, at in entries:
        print("%s  deleted %s  purged after %s  %d revs  (%s)" % (
            vid, _iso(at), (at + timedelta(days=TRASH_DAYS)).date().isoformat(),
            len(_rev_metas(os.path.join(C.trash_dir(cfg), name))), name))


def purge_trash(cfg, now=None, dry_run=False):
    """Permanently delete trash entries older than TRASH_DAYS. Returns the purged entry names."""
    now = now or _utcnow()
    purged = []
    for name, vid, at in _trash_entries(cfg):
        if now - at <= timedelta(days=TRASH_DAYS):
            continue
        p = os.path.join(C.trash_dir(cfg), name)
        print(("would purge " if dry_run else "purge ") + "%s (vault %s, deleted %s)" % (p, vid, _iso(at)))
        if not dry_run:
            shutil.rmtree(p)
        purged.append(name)
    print("purge-trash: %d trash entr%s %s" % (len(purged), "y" if len(purged) == 1 else "ies",
                                              "to purge" if dry_run else "purged"))
    return purged


def cmd_purge_trash(cfg, args):
    purge_trash(cfg, dry_run=args.dry_run)


def main():
    ap = argparse.ArgumentParser(prog="mvault-admin")
    ap.add_argument("--config", default=None)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init-ca")
    p = sub.add_parser("pair"); p.add_argument("name")
    sub.add_parser("list")
    p = sub.add_parser("revoke"); p.add_argument("name")
    p = sub.add_parser("retention"); p.add_argument("--dry-run", action="store_true")
    sub.add_parser("stats")
    p = sub.add_parser("delete-vault"); p.add_argument("vault_id"); p.add_argument("--yes", action="store_true")
    p = sub.add_parser("undelete-vault"); p.add_argument("vault_id"); p.add_argument("--entry", default=None)
    sub.add_parser("trash")
    p = sub.add_parser("purge-trash"); p.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    cfg = C.load_config(args.config)
    {"init-ca": cmd_init_ca, "pair": cmd_pair, "list": cmd_list, "revoke": cmd_revoke,
     "retention": cmd_retention, "stats": cmd_stats,
     "delete-vault": cmd_delete_vault, "undelete-vault": cmd_undelete_vault, "trash": cmd_trash,
     "purge-trash": cmd_purge_trash}[args.cmd](cfg, args)


if __name__ == "__main__":
    main()
