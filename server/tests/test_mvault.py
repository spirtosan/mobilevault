"""Integration tests for the mvault backup server. Run from server/:  python3 -m unittest -v"""
import argparse
import contextlib
import hashlib
import io
import json
import os
import re
import struct
import subprocess
import tempfile
import threading
import unittest
import urllib.parse
import uuid
from datetime import datetime, timedelta, timezone
from http.client import HTTPConnection

from mvault import admin, common as C, server

VID = str(uuid.uuid4())


def make_blob(vid, rev, payload=b"x" * 64):
    hdr = json.dumps({"v": 2, "vault_id": vid, "rev": rev, "slots": []}).encode()
    return C.MAGIC_V2 + struct.pack(">I", len(hdr)) + hdr + os.urandom(12) + payload + os.urandom(16)


class MvaultTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        t = cls.tmp.name
        cls.cfg = dict(C.DEFAULTS, port=0, data_dir=t + "/data", state_dir=t + "/state", ca_dir=t + "/ca",
                       public_url="https://vault.example.org/api/v1", max_body=4096)
        with contextlib.redirect_stdout(io.StringIO()):
            admin.cmd_init_ca(cls.cfg, None)
        cls.srv = server.Server(cls.cfg)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.cert, cls.token = cls.pair("phone1")

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.tmp.cleanup()

    @classmethod
    def new_code(cls, name):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            admin.cmd_pair(cls.cfg, argparse.Namespace(name=name))
        return re.search(r"Code: (\w{5})-(\w{5})", out.getvalue()).group(0)[6:].replace("-", "")

    @classmethod
    def csr(cls):
        d = tempfile.mkdtemp(dir=cls.tmp.name)
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", d + "/k"], check=True)
        return subprocess.run(["openssl", "req", "-new", "-key", d + "/k", "-subj", "/CN=phone"],
                              capture_output=True, text=True, check=True).stdout

    @classmethod
    def req(cls, method, path, body=None, headers=None):
        c = HTTPConnection("127.0.0.1", cls.port, timeout=10)
        c.request(method, "/api/v1" + path, body=body, headers=headers or {})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data, r

    @classmethod
    def pair(cls, name):
        code = cls.new_code(name)
        st, data, _ = cls.req("POST", "/pair", json.dumps({"code": code, "csr_pem": cls.csr()}))
        assert st == 201, (st, data)
        j = json.loads(data)
        return j["cert_pem"], j["token"]

    def auth(self, cert=None, token=None):
        return {"X-Client-Verify": "SUCCESS",
                "X-Client-Cert": urllib.parse.quote(cert or self.cert),
                "Authorization": "Bearer " + (token or self.token)}

    def put(self, vid, rev, blob, headers=None):
        h = dict(headers or self.auth())
        h["X-Content-SHA256"] = hashlib.sha256(blob).hexdigest()
        return self.req("PUT", f"/vaults/{vid}/revs/{rev}", blob, h)

    # ── tests ──
    def test_health(self):
        st, data, _ = self.req("GET", "/health")
        self.assertEqual(st, 200)
        self.assertTrue(json.loads(data)["ok"])

    def test_upload_list_download_latest(self):
        vid = str(uuid.uuid4())
        b1, b2 = make_blob(vid, 1), make_blob(vid, 2)
        self.assertEqual(self.put(vid, 1, b1)[0], 201)
        self.assertEqual(self.put(vid, 2, b2)[0], 201)
        st, data, _ = self.req("GET", f"/vaults/{vid}/revs", headers=self.auth())
        self.assertEqual([m["rev"] for m in json.loads(data)["revs"]], [1, 2])
        st, data, r = self.req("GET", f"/vaults/{vid}/revs/1", headers=self.auth())
        self.assertEqual((st, data), (200, b1))
        self.assertEqual(r.getheader("X-Content-SHA256"), hashlib.sha256(b1).hexdigest())
        st, data, _ = self.req("GET", f"/vaults/{vid}/latest", headers=self.auth())
        self.assertEqual(json.loads(data)["rev"], 2)
        mode = os.stat(os.path.join(self.cfg["data_dir"], vid, C.rev_filename(1))).st_mode & 0o777
        self.assertEqual(mode, 0o440)

    def test_no_overwrite(self):
        vid = str(uuid.uuid4())
        self.assertEqual(self.put(vid, 1, make_blob(vid, 1))[0], 201)
        self.assertEqual(self.put(vid, 1, make_blob(vid, 1))[0], 409)

    def test_no_delete(self):
        st, _, _ = self.req("DELETE", f"/vaults/{VID}/revs/1", headers=self.auth())
        self.assertEqual(st, 405)

    def test_validation(self):
        vid = str(uuid.uuid4())
        self.assertEqual(self.put(vid, 2, make_blob(vid, 1))[0], 422)                 # rev mismatch
        self.assertEqual(self.put(vid, 1, make_blob(str(uuid.uuid4()), 1))[0], 422)   # vault mismatch
        self.assertEqual(self.put(vid, 1, b"PVAULT1\x00" + b"x" * 80)[0], 422)        # v1 / bad magic
        self.assertEqual(self.put(vid, 1, make_blob(vid, 1, b"x" * 5000))[0], 413)    # too large
        b = make_blob(vid, 1)
        h = self.auth(); h["X-Content-SHA256"] = "0" * 64
        self.assertEqual(self.req("PUT", f"/vaults/{vid}/revs/1", b, h)[0], 422)      # hash mismatch
        self.assertEqual(self.req("GET", "/vaults/not-a-uuid/revs", headers=self.auth())[0], 404)

    def test_auth_failures(self):
        p = f"/vaults/{VID}/revs"
        self.assertEqual(self.req("GET", p)[0], 403)                                  # no cert
        h = self.auth(); h["X-Client-Verify"] = "NONE"
        self.assertEqual(self.req("GET", p, headers=h)[0], 403)
        self.assertEqual(self.req("GET", p, headers=self.auth(token="wrong"))[0], 401)
        cert2, tok2 = self.pair("phone2")
        self.assertEqual(self.req("GET", p, headers=self.auth(cert2, self.token))[0], 401)  # token not bound to cert
        self.assertEqual(self.req("GET", p, headers=self.auth(cert2, tok2))[0], 200)
        with contextlib.redirect_stdout(io.StringIO()):
            admin.cmd_revoke(self.cfg, argparse.Namespace(name="phone2"))
        self.assertEqual(self.req("GET", p, headers=self.auth(cert2, tok2))[0], 403)

    def test_pairing_code_single_use_and_bad_code(self):
        code = self.new_code("phone3")
        csr = self.csr()
        self.assertEqual(self.req("POST", "/pair", json.dumps({"code": code, "csr_pem": csr}))[0], 201)
        self.assertEqual(self.req("POST", "/pair", json.dumps({"code": code, "csr_pem": csr}))[0], 403)
        self.assertEqual(self.req("POST", "/pair", json.dumps({"code": "AAAAAAAAAA", "csr_pem": csr}))[0], 403)
        self.assertEqual(self.req("POST", "/pair", json.dumps({"code": self.new_code("phone4"),
                                                              "csr_pem": "garbage"}))[0], 400)

    def test_pairing_attempt_limit(self):
        code = self.new_code("phone5")
        for _ in range(5):
            self.req("POST", "/pair", json.dumps({"code": "WRONGWRONG", "csr_pem": "x"}))
        self.assertEqual(self.req("POST", "/pair", json.dumps({"code": code, "csr_pem": self.csr()}))[0], 403)

    def test_retention_plan(self):
        now = datetime(2026, 10, 29, tzinfo=timezone.utc)
        revs = [{"rev": i + 1, "received": (now - timedelta(days=400 - i)).isoformat()} for i in range(400)]
        doomed = admin.plan_retention(revs, now)
        kept = {m["rev"] for m in revs} - doomed
        self.assertTrue({371 + i for i in range(30)} <= kept)        # last 30
        self.assertIn(400, kept)
        self.assertLessEqual(len(kept), 30 + 53)
        self.assertNotIn(1, kept)                                    # older than a year
        self.assertEqual(admin.plan_retention([], now), set())


if __name__ == "__main__":
    unittest.main()
