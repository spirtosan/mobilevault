# Mobile Vault backup server: admin CLI (run as root)
# Copyright (C) 2026  github.com/spirtosan, GPL-3.0-or-later
"""Usage:
  mvault-admin init-ca                 create the private client CA (once)
  mvault-admin pair <device-name>      one-time pairing code + QR (valid 10 min)
  mvault-admin list                    paired devices
  mvault-admin revoke <device-name>    block a lost/stolen device immediately
  mvault-admin retention [--dry-run]   prune old revisions (systemd timer)
  mvault-admin stats                   vaults, revision counts, sizes
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
from datetime import datetime, timezone

from . import common as C

KEEP_LAST = 30
KEEP_WEEKS = 52
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
        print("%-24s %-8s created %s  last seen %s  cert %s…" % (
            n, "REVOKED" if d.get("revoked") else "active", d.get("created"),
            d.get("last_seen", "-"), d.get("cert_sha256", "")[:16]))


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
            print("%s  %4d revs  %8d bytes  latest r%s at %s" % (
                vid, len(revs), size, last.get("rev"), last.get("received")))


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
    args = ap.parse_args()
    cfg = C.load_config(args.config)
    {"init-ca": cmd_init_ca, "pair": cmd_pair, "list": cmd_list, "revoke": cmd_revoke,
     "retention": cmd_retention, "stats": cmd_stats}[args.cmd](cfg, args)


if __name__ == "__main__":
    main()
