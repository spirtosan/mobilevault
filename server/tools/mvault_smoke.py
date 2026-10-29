"""End-to-end smoke test against a deployed mvault server, through nginx/TLS/mTLS.
Acts like a phone: makes an EC key + CSR, pairs with a one-time code, uploads a fake
PVAULT2 file, lists, downloads, checks that overwrite is refused.

Usage (on any machine with python3 + openssl):
  1. on the server:  sudo mvault-admin pair smoketest      → note the code
  2. python3 mvault_smoke.py https://vault.<DOMAIN>/api/v1 <CODE>
  3. on the server:  sudo mvault-admin revoke smoketest
     and remove the printed test vault directory as root.
"""
import hashlib
import json
import os
import ssl
import struct
import subprocess
import sys
import tempfile
import urllib.parse
import uuid
from http.client import HTTPSConnection


def main():
    base, code = sys.argv[1].rstrip("/"), sys.argv[2].replace("-", "").upper()
    u = urllib.parse.urlsplit(base)
    work = tempfile.mkdtemp(prefix="mvault-smoke-")
    key, crt = os.path.join(work, "k.pem"), os.path.join(work, "c.pem")
    subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key], check=True)
    csr = subprocess.run(["openssl", "req", "-new", "-key", key, "-subj", "/CN=smoketest"],
                         capture_output=True, text=True, check=True).stdout

    def conn(with_cert):
        ctx = ssl.create_default_context()
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        if with_cert:
            ctx.load_cert_chain(crt, key)
        return HTTPSConnection(u.hostname, u.port or 443, context=ctx, timeout=20)

    def call(method, path, body=None, headers=None, with_cert=True):
        c = conn(with_cert)
        c.request(method, u.path + path, body=body, headers=headers or {})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data

    ok = True

    def check(label, got, want):
        nonlocal ok
        good = got == want
        ok &= good
        print(("PASS " if good else "FAIL ") + label + f"  (got {got}, want {want})")

    st, _ = call("GET", "/health", with_cert=False)
    check("health without cert", st, 200)
    st, _ = call("GET", f"/vaults/{uuid.uuid4()}/revs", with_cert=False)
    check("API without client cert is refused", st, 403)

    st, data = call("POST", "/pair", json.dumps({"code": code, "csr_pem": csr}),
                    {"Content-Type": "application/json"}, with_cert=False)
    check("pairing", st, 201)
    if st != 201:
        print(data.decode(errors="replace"))
        sys.exit(1)
    j = json.loads(data)
    with open(crt, "w") as f:
        f.write(j["cert_pem"])
    auth = {"Authorization": "Bearer " + j["token"]}

    vid = str(uuid.uuid4())
    hdr = json.dumps({"v": 2, "vault_id": vid, "rev": 1, "slots": [], "smoke_test": True}).encode()
    blob = b"PVAULT2\x00" + struct.pack(">I", len(hdr)) + hdr + os.urandom(12 + 64 + 16)
    h = dict(auth, **{"X-Content-SHA256": hashlib.sha256(blob).hexdigest(),
                      "Content-Type": "application/octet-stream"})
    check("upload rev 1", call("PUT", f"/vaults/{vid}/revs/1", blob, h)[0], 201)
    check("overwrite refused", call("PUT", f"/vaults/{vid}/revs/1", blob, h)[0], 409)
    check("delete refused", call("DELETE", f"/vaults/{vid}/revs/1", None, auth)[0], 405)
    st, data = call("GET", f"/vaults/{vid}/revs", None, auth)
    check("list", (st, [m["rev"] for m in json.loads(data)["revs"]] if st == 200 else None), (200, [1]))
    st, data = call("GET", f"/vaults/{vid}/revs/1", None, auth)
    check("download matches", (st, data == blob), (200, True))
    check("wrong token refused", call("GET", f"/vaults/{vid}/revs", None,
                                      {"Authorization": "Bearer nope"})[0], 401)
    print("\nTest vault id (delete its directory on the server as root):", vid)
    print("ALL PASSED" if ok else "SOME CHECKS FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
