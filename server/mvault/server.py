# Mobile Vault backup server: HTTP service (runs behind nginx)
# Copyright (C) 2026  github.com/spirtosan, GPL-3.0-or-later
"""Append-only backup API. Listens on 127.0.0.1 only; nginx terminates TLS,
verifies the client certificate and passes these headers (it overwrites any
values sent by the client):

    X-Client-Verify: $ssl_client_verify        (SUCCESS / NONE / FAILED:...)
    X-Client-Cert:   $ssl_client_escaped_cert  (URL-escaped PEM)

Endpoints (prefix /api/v1):
    GET  /health
    POST /pair                               (no client cert needed; one-time code)
    PUT  /vaults/{id}/revs/{rev}             (cert + bearer token)
    GET  /vaults/{id}/revs                   (cert + bearer token)
    GET  /vaults/{id}/revs/{rev}             (cert + bearer token)
    GET  /vaults/{id}/latest                 (cert + bearer token)
There is intentionally no DELETE and no overwrite.
"""

import hashlib
import hmac
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import common as C

VERSION = "1"
PREFIX = "/api/v1"
ROUTE_REVS = re.compile(r"^/vaults/([0-9a-f-]{36})/revs$")
ROUTE_REV = re.compile(r"^/vaults/([0-9a-f-]{36})/revs/(\d{1,16})$")
ROUTE_LATEST = re.compile(r"^/vaults/([0-9a-f-]{36})/latest$")
LAST_SEEN_INTERVAL = 3600


class ApiError(Exception):
    def __init__(self, status, msg):
        super().__init__(msg)
        self.status, self.msg = status, msg


def sign_csr(cfg, csr_pem: str, device_name: str) -> str:
    """Sign a client CSR with the private CA using the openssl CLI.
    Accepts only EC P-256/P-384 or RSA >= 2048 keys, and a valid self-signature."""
    with tempfile.TemporaryDirectory() as td:
        csr = os.path.join(td, "req.csr")
        with open(csr, "w", encoding="ascii") as f:
            f.write(csr_pem)
        r = subprocess.run(["openssl", "req", "-in", csr, "-noout", "-verify", "-text"],
                           capture_output=True, text=True, timeout=10)
        if r.returncode != 0:
            raise ApiError(400, "invalid CSR")
        txt = r.stdout
        ok = "ASN1 OID: prime256v1" in txt or "ASN1 OID: secp384r1" in txt
        bits = re.search(r"Public-Key: \((\d+) bit\)", txt)
        if "rsaEncryption" in txt and bits and int(bits.group(1)) >= 2048:
            ok = True
        if not ok:
            raise ApiError(400, "unsupported key type (use EC P-256)")
        ext = os.path.join(td, "ext.cnf")
        with open(ext, "w", encoding="ascii") as f:
            f.write("basicConstraints=critical,CA:FALSE\n"
                    "keyUsage=critical,digitalSignature\n"
                    "extendedKeyUsage=clientAuth\n")
        out = os.path.join(td, "client.crt")
        serial = "0x" + secrets.token_hex(16)
        r = subprocess.run(
            ["openssl", "x509", "-req", "-in", csr,
             "-CA", os.path.join(cfg["ca_dir"], "ca.crt"),
             "-CAkey", os.path.join(cfg["ca_dir"], "ca.key"),
             "-set_serial", serial, "-days", str(int(cfg["client_cert_days"])),
             "-sha256", "-extfile", ext, "-out", out],
            capture_output=True, text=True, timeout=10)
        if r.returncode != 0:
            raise ApiError(500, "signing failed")
        with open(out, encoding="ascii") as f:
            return f.read()


class Handler(BaseHTTPRequestHandler):
    server_version = "mvault/" + VERSION
    sys_version = ""
    protocol_version = "HTTP/1.1"

    # ── plumbing ────────────────────────────────────────────────────────────
    @property
    def cfg(self):
        return self.server.cfg

    def log_message(self, fmt, *args):  # journald gets stderr; never log bodies or tokens
        sys.stderr.write((fmt % args) + "\n")

    def _send(self, status, body=b"", ctype="application/json", extra=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, status, obj):
        self._send(status, json.dumps(obj).encode("utf-8"))

    def _read_body(self, limit):
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise ApiError(411, "Content-Length required")
        if n < 0 or n > limit:
            raise ApiError(413, "body too large")
        data = self.rfile.read(n)
        if len(data) != n:
            raise ApiError(400, "short body")
        return data

    def _path(self):
        p = urllib.parse.urlsplit(self.path).path
        if not p.startswith(PREFIX + "/") and p != PREFIX:
            raise ApiError(404, "not found")
        return p[len(PREFIX):] or "/"

    def _dispatch(self, method):
        try:
            getattr(self, "_h_" + method)()
        except ApiError as e:
            self.close_connection = True
            self._json(e.status, {"error": e.msg})
        except Exception as e:  # noqa: BLE001
            sys.stderr.write("internal error: %r\n" % (e,))
            self.close_connection = True
            self._json(500, {"error": "internal error"})

    def do_GET(self):
        self._dispatch("get")

    def do_PUT(self):
        self._dispatch("put")

    def do_POST(self):
        self._dispatch("post")

    def do_DELETE(self):
        self._json(405, {"error": "append-only: delete is not supported"})

    do_PATCH = do_DELETE

    # ── auth ────────────────────────────────────────────────────────────────
    def _auth(self):
        if self.headers.get("X-Client-Verify") != "SUCCESS":
            raise ApiError(403, "client certificate required")
        raw = self.headers.get("X-Client-Cert", "")
        try:
            fpr = C.cert_sha256(urllib.parse.unquote(raw))
        except Exception:
            raise ApiError(403, "client certificate missing")
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            raise ApiError(401, "token required")
        tok_hash = C.sha256_hex(auth[7:].strip().encode("utf-8"))
        store = self.server.devices
        with store.lock:
            devices = store.load()
            for name, d in devices.items():
                if hmac.compare_digest(d.get("cert_sha256", ""), fpr):
                    if d.get("revoked"):
                        raise ApiError(403, "device revoked")
                    if not hmac.compare_digest(d.get("token_sha256", ""), tok_hash):
                        raise ApiError(401, "bad token")
                    now = int(time.time())
                    if now - d.get("last_seen_ts", 0) > LAST_SEEN_INTERVAL:
                        d["last_seen_ts"], d["last_seen"] = now, C.now_iso()
                        store.save(devices)
                    return name
        raise ApiError(403, "unknown device")

    # ── handlers ────────────────────────────────────────────────────────────
    def _h_get(self):
        p = self._path()
        if p == "/health":
            return self._json(200, {"ok": True, "version": VERSION})
        device = self._auth()
        m = ROUTE_REVS.match(p)
        if m:
            vid = self._vid(m.group(1))
            return self._json(200, {"vault_id": vid, "revs": C.list_revisions(self.cfg, vid)})
        m = ROUTE_LATEST.match(p)
        if m:
            vid = self._vid(m.group(1))
            revs = C.list_revisions(self.cfg, vid)
            if not revs:
                raise ApiError(404, "no revisions")
            return self._json(200, dict(revs[-1], vault_id=vid))
        m = ROUTE_REV.match(p)
        if m:
            vid, rev = self._vid(m.group(1)), int(m.group(2))
            try:
                data = C.read_revision(self.cfg, vid, rev)
            except FileNotFoundError:
                raise ApiError(404, "no such revision")
            return self._send(200, data, "application/octet-stream",
                              {"X-Content-SHA256": C.sha256_hex(data)})
        del device
        raise ApiError(404, "not found")

    def _h_put(self):
        p = self._path()
        m = ROUTE_REV.match(p)
        if not m:
            raise ApiError(404, "not found")
        device = self._auth()
        vid, rev = self._vid(m.group(1)), int(m.group(2))
        if not (1 <= rev < C.MAX_REV):
            raise ApiError(422, "bad rev")
        want = (self.headers.get("X-Content-SHA256") or "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", want):
            raise ApiError(400, "X-Content-SHA256 header required")
        blob = self._read_body(int(self.cfg["max_body"]))
        if not hmac.compare_digest(C.sha256_hex(blob), want):
            raise ApiError(422, "sha256 mismatch")
        try:
            hdr = C.parse_pvault2_header(blob)
        except C.VaultFormatError as e:
            raise ApiError(422, str(e))
        if hdr["vault_id"] != vid or hdr["rev"] != rev:
            raise ApiError(422, "vault_id/rev do not match the file header")
        try:
            meta = C.store_revision(self.cfg, vid, rev, blob, device)
        except C.AlreadyExists:
            raise ApiError(409, "revision already exists")
        return self._json(201, dict(meta, vault_id=vid))

    def _h_post(self):
        p = self._path()
        if p != "/pair":
            raise ApiError(404, "not found")
        body = self._read_body(16 * 1024)
        try:
            req = json.loads(body.decode("utf-8"))
            code, csr = str(req["code"]).strip().upper(), str(req["csr_pem"])
        except Exception:
            raise ApiError(400, "expected JSON {code, csr_pem}")
        code_hash = C.sha256_hex(code.encode("utf-8"))
        pstore, dstore = self.server.pairings, self.server.devices
        with pstore.lock:
            pairings = pstore.load()
            now = time.time()
            # drop expired / exhausted entries
            for k in [k for k, v in pairings.items()
                      if v["expires_ts"] < now or v["attempts"] >= self.cfg["pair_max_attempts"]]:
                del pairings[k]
            entry = pairings.get(code_hash)
            if not entry:
                # count a failed attempt against every pending pairing (limits online guessing)
                for v in pairings.values():
                    v["attempts"] += 1
                pstore.save(pairings)
                raise ApiError(403, "invalid or expired pairing code")
            name = entry["name"]
            del pairings[code_hash]          # single use, even if signing fails below
            pstore.save(pairings)
        cert_pem = sign_csr(self.cfg, csr, name)
        token = secrets.token_urlsafe(32)
        with dstore.lock:
            devices = dstore.load()
            devices[name] = {"cert_sha256": C.cert_sha256(cert_pem),
                             "token_sha256": C.sha256_hex(token.encode("utf-8")),
                             "created": C.now_iso(), "revoked": False}
            dstore.save(devices)
        with open(os.path.join(self.cfg["ca_dir"], "ca.crt"), encoding="ascii") as f:
            ca_pem = f.read()
        return self._json(201, {"device": name, "cert_pem": cert_pem, "ca_pem": ca_pem, "token": token})

    def _vid(self, v):
        if not C.UUID_RE.match(v):
            raise ApiError(400, "bad vault_id")
        return v


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, cfg):
        self.cfg = cfg
        self.devices = C.devices_store(cfg)
        self.pairings = C.pairings_store(cfg)
        os.makedirs(cfg["data_dir"], mode=0o750, exist_ok=True)
        super().__init__((cfg["listen"], int(cfg["port"])), Handler)


def main():
    cfg = C.load_config(sys.argv[1] if len(sys.argv) > 1 else None)
    if cfg["listen"] not in ("127.0.0.1", "::1", "localhost"):
        sys.exit("refusing to listen on a non-loopback address; nginx must be in front")
    os.umask(0o027)
    srv = Server(cfg)
    sys.stderr.write("mvault %s listening on %s:%s\n" % (VERSION, cfg["listen"], cfg["port"]))
    srv.serve_forever()


if __name__ == "__main__":
    main()
