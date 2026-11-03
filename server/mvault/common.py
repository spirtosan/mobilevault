# Mobile Vault backup server: shared helpers
# Copyright (C) 2026  github.com/spirtosan, GPL-3.0-or-later
"""Config, state files, PVAULT2 header parsing and append-only storage.
Python 3.10+ standard library only."""

import base64
import hashlib
import json
import os
import re
import struct
import subprocess
import tempfile
import threading
from datetime import datetime, timedelta, timezone

DEFAULT_CONFIG_PATH = "/etc/mvault/config.json"

DEFAULTS = {
    "listen": "127.0.0.1",
    "port": 8790,
    "data_dir": "/srv/mobilevault/data",
    "state_dir": "/var/lib/mvault",
    "ca_dir": "/etc/mvault/ca",
    "public_url": "https://vault.example.org/api/v1",
    "server_cert": "",            # e.g. /etc/letsencrypt/live/vault.<DOMAIN>/cert.pem (for the pin in the QR)
    "backup_pins": [],            # "sha256/…" of spare key(s) kept offline, for key rotation
    "max_body": 5 * 1024 * 1024,
    "pair_ttl_seconds": 600,
    "pair_max_attempts": 5,
    "client_cert_days": 825,
}

MAGIC_V2 = b"PVAULT2\x00"
MAX_HEADER = 64 * 1024
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$")
MAX_REV = 2 ** 53


def load_config(path=None):
    path = path or os.environ.get("MVAULT_CONFIG", DEFAULT_CONFIG_PATH)
    cfg = dict(DEFAULTS)
    with open(path, encoding="utf-8") as f:
        cfg.update(json.load(f))
    return cfg


def now_iso():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ── Atomic JSON state (devices / pairings) ────────────────────────────────────
class JsonStore:
    """Small JSON file with a process-wide lock and atomic replace."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()

    def load(self):
        with self.lock:
            try:
                with open(self.path, encoding="utf-8") as f:
                    return json.load(f)
            except FileNotFoundError:
                return {}

    def save(self, data):
        with self.lock:
            d = os.path.dirname(self.path)
            os.makedirs(d, mode=0o750, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=d, prefix=".tmp-")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump(data, f, indent=2, sort_keys=True)
                    f.flush()
                    os.fsync(f.fileno())
                os.chmod(tmp, 0o640)
                if hasattr(os, "geteuid") and os.geteuid() == 0:
                    # admin CLI runs as root: keep files owned like their directory (the service user)
                    st = os.stat(d)
                    os.chown(tmp, st.st_uid, st.st_gid)
                os.replace(tmp, self.path)
            except BaseException:
                try:
                    os.unlink(tmp)
                except FileNotFoundError:
                    pass
                raise


def devices_store(cfg):
    return JsonStore(os.path.join(cfg["state_dir"], "devices.json"))


def pairings_store(cfg):
    return JsonStore(os.path.join(cfg["state_dir"], "pairings.json"))


# ── Certificates ──────────────────────────────────────────────────────────────
def pem_to_der(pem: str) -> bytes:
    m = re.search(r"-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", pem, re.S)
    if not m:
        raise ValueError("no certificate in PEM")
    return base64.b64decode("".join(m.group(1).split()))


def cert_sha256(pem: str) -> str:
    return sha256_hex(pem_to_der(pem))


def cert_not_after(pem: str):
    """notAfter of a certificate as ISO-8601 UTC ("2029-02-05T12:31:18Z"), or None (openssl CLI)."""
    try:
        r = subprocess.run(["openssl", "x509", "-noout", "-enddate"], input=pem, capture_output=True, text=True, timeout=10)
        s = r.stdout.strip().split("=", 1)[1]                      # "Feb  5 12:31:18 2029 GMT"
        dt = datetime.strptime(" ".join(s.split()[:4]), "%b %d %H:%M:%S %Y").replace(tzinfo=timezone.utc)
        return dt.isoformat().replace("+00:00", "Z")
    except Exception:
        return None


def device_cert_until(cfg, d):
    """Expiry date of a device's client certificate for `mvault-admin list`: stored at pairing (Task 08),
    otherwise estimated from the pairing time + client_cert_days (marked with "~")."""
    if d.get("cert_not_after"):
        return d["cert_not_after"][:10]
    try:
        created = datetime.fromisoformat(d["created"].replace("Z", "+00:00"))
        return "~" + (created + timedelta(days=int(cfg["client_cert_days"]))).date().isoformat()
    except Exception:
        return "?"


# ── PVAULT2 header ────────────────────────────────────────────────────────────
class VaultFormatError(ValueError):
    pass


def parse_pvault2_header(blob: bytes) -> dict:
    """Validate the outer structure of a PVAULT2 file and return its header JSON.
    The server never decrypts anything; it only checks structure."""
    if len(blob) < 12 or blob[:8] != MAGIC_V2:
        raise VaultFormatError("bad magic (expected PVAULT2)")
    (hlen,) = struct.unpack(">I", blob[8:12])
    if hlen == 0 or hlen > MAX_HEADER:
        raise VaultFormatError("bad header length")
    # header + 12-byte nonce + at least the 16-byte GCM tag
    if len(blob) < 12 + hlen + 12 + 16:
        raise VaultFormatError("file truncated")
    try:
        header = json.loads(blob[12:12 + hlen].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise VaultFormatError("header is not valid JSON")
    if not isinstance(header, dict) or header.get("v") != 2:
        raise VaultFormatError("unsupported header version")
    if not isinstance(header.get("vault_id"), str) or not UUID_RE.match(header["vault_id"]):
        raise VaultFormatError("bad vault_id in header")
    rev = header.get("rev")
    if not isinstance(rev, int) or isinstance(rev, bool) or not (1 <= rev < MAX_REV):
        raise VaultFormatError("bad rev in header")
    return header


# ── Append-only storage ───────────────────────────────────────────────────────
class AlreadyExists(Exception):
    pass


def rev_filename(rev: int) -> str:
    return f"r{rev:016d}.pvault"


def _fsync_dir(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _write_new(dirpath, final_name, data: bytes, mode=0o440):
    """Write data to dirpath/final_name. Fails with AlreadyExists if the name is taken.
    Uses tmp file + fsync + link() so the final name appears atomically and is never overwritten."""
    fd, tmp = tempfile.mkstemp(dir=dirpath, prefix=".up-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, mode)
        try:
            os.link(tmp, os.path.join(dirpath, final_name))
        except FileExistsError:
            raise AlreadyExists(final_name)
    finally:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass


def store_revision(cfg, vault_id, rev, blob, device_name):
    vdir = os.path.join(cfg["data_dir"], vault_id)
    os.makedirs(vdir, mode=0o750, exist_ok=True)
    name = rev_filename(rev)
    meta = {"rev": rev, "size": len(blob), "sha256": sha256_hex(blob),
            "received": now_iso(), "device": device_name}
    _write_new(vdir, name, blob)
    # Sidecar metadata; if this fails the blob is still stored and meta is rebuilt on read.
    try:
        _write_new(vdir, name + ".json", json.dumps(meta).encode("utf-8"))
    except AlreadyExists:
        pass
    _fsync_dir(vdir)
    return meta


def list_revisions(cfg, vault_id):
    vdir = os.path.join(cfg["data_dir"], vault_id)
    out = []
    try:
        names = os.listdir(vdir)
    except FileNotFoundError:
        return out
    for n in names:
        m = REV_FILE_RE.match(n)
        if m:
            out.append(_rev_meta(vdir, n, int(m.group(1))))
    out.sort(key=lambda m: m["rev"])
    return out


REV_FILE_RE = re.compile(r"^r(\d{16})\.pvault$")


def _rev_meta(vdir, name, rev):
    """Metadata of one stored revision: the sidecar JSON, or rebuilt from the blob if it is missing."""
    try:
        with open(os.path.join(vdir, name + ".json"), encoding="utf-8") as f:
            meta = json.load(f)
        if meta:
            return meta
    except (FileNotFoundError, json.JSONDecodeError):
        pass
    p = os.path.join(vdir, name)
    with open(p, "rb") as f:
        data = f.read()
    st = os.stat(p)
    return {"rev": rev, "size": len(data), "sha256": sha256_hex(data),
            "received": datetime.fromtimestamp(st.st_mtime, timezone.utc)
            .replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            "device": None}


def list_vaults(cfg):
    """One row per vault that has at least one revision, newest upload first.
    Reads only the metadata of each vault's highest rev."""
    out = []
    try:
        names = os.listdir(cfg["data_dir"])
    except FileNotFoundError:
        return out
    for vid in names:
        vdir = os.path.join(cfg["data_dir"], vid)
        if not UUID_RE.match(vid) or not os.path.isdir(vdir):
            continue
        revs = [(int(m.group(1)), n) for n in os.listdir(vdir) for m in [REV_FILE_RE.match(n)] if m]
        if not revs:
            continue
        rev, name = max(revs)
        meta = _rev_meta(vdir, name, rev)
        out.append({"vault_id": vid, "latest_rev": rev, "latest_time": meta.get("received"),
                    "size": meta.get("size"), "device": meta.get("device"), "revisions": len(revs)})
    out.sort(key=lambda v: (v["latest_time"] or "", v["latest_rev"]), reverse=True)
    return out


def trash_dir(cfg):
    """Root-only trash for deleted vaults (`mvault-admin delete-vault`). Default: next to the data
    directory (`<data_dir>/../trash`), outside the service user's writable tree, same disk (so the
    move is an atomic rename)."""
    return cfg.get("trash_dir") or os.path.join(os.path.dirname(os.path.normpath(cfg["data_dir"])), "trash")


def read_revision(cfg, vault_id, rev):
    p = os.path.join(cfg["data_dir"], vault_id, rev_filename(rev))
    with open(p, "rb") as f:
        return f.read()
