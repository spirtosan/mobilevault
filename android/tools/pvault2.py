"""Reference implementation of the PVAULT2 format in Python (independent of the Kotlin code).
Used to cross-check the Android implementation and as a starting point for desktop support.

    python3 pvault2.py create <out> <password> [--hint H] [--m 65536 --t 3 --p 1]   → prints recovery key
    python3 pvault2.py open   <file> <password>                                     → prints payload JSON
    python3 pvault2.py recover <file> <RECOVERY-KEY>                                → prints payload JSON

Requires: pip install cryptography argon2-cffi
"""
import argparse
import base64
import hashlib
import json
import os
import struct
import sys
import unicodedata
import uuid
from datetime import datetime, timezone

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MAGIC = b"PVAULT2\x00"
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
b64 = lambda b: base64.b64encode(b).decode()
unb64 = base64.b64decode


def now():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def slot_aad(t, vid):
    return f"PVAULT2-slot:{t}:{vid}".encode()


def password_kek(pw, kdf):
    assert kdf["alg"] == "argon2id"
    return hash_secret_raw(unicodedata.normalize("NFC", pw).encode("utf-8"), unb64(kdf["salt"]),
                           time_cost=kdf["t"], memory_cost=kdf["m_kib"], parallelism=kdf["p"],
                           hash_len=32, type=Type.ID, version=19)


def recovery_kek(key, kdf):
    assert kdf["alg"] == "hkdf-sha256"
    return HKDF(hashes.SHA256(), 32, unb64(kdf["salt"]), b"PVAULT2 recovery").derive(key)


# ── recovery key text form ──
def _b32(b):
    n, bits, out = 0, 0, ""
    for x in b:
        n, bits = (n << 8) | x, bits + 8
        while bits >= 5:
            out += ALPHABET[(n >> (bits - 5)) & 31]; bits -= 5
    return out


def _checksum(key):
    h = hashlib.sha256(key).digest()
    v = (h[0] << 12) | (h[1] << 4) | (h[2] >> 4)
    return "".join(ALPHABET[(v >> (15 - 5 * i)) & 31] for i in range(4))


def rk_display(key):
    s = _b32(key) + _checksum(key)
    return "-".join(s[i:i + 4] for i in range(0, 36, 4))


def rk_parse(text):
    s = "".join(c for c in text.upper() if c not in "- \t\n").translate(str.maketrans("OIL", "011"))
    assert len(s) == 36, "recovery key must have 36 characters"
    n, bits, out = 0, 0, bytearray()
    for c in s[:32]:
        n, bits = (n << 5) | ALPHABET.index(c), bits + 5
        if bits >= 8:
            out.append((n >> (bits - 8)) & 0xFF); bits -= 8
    key = bytes(out)
    assert _checksum(key) == s[32:], "checksum mismatch"
    return key


# ── file ──
def encode(header, dek, payload):
    h = json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode()
    aad = MAGIC + struct.pack(">I", len(h)) + h
    nonce = os.urandom(12)
    return aad + nonce + AESGCM(dek).encrypt(nonce, json.dumps(payload, ensure_ascii=False).encode(), aad)


def split(raw):
    assert raw[:8] == MAGIC, "not PVAULT2"
    (hl,) = struct.unpack(">I", raw[8:12])
    hb = raw[12:12 + hl]
    return json.loads(hb), raw[:12 + hl], raw[12 + hl:24 + hl], raw[24 + hl:]


def unlock(raw, kek_fn, slot_type):
    header, aad, nonce, ct = split(raw)
    slot = next(s for s in header["slots"] if s["type"] == slot_type)
    kek = kek_fn(slot["kdf"])
    dek = AESGCM(kek).decrypt(unb64(slot["nonce"]), unb64(slot["wrapped_dek"]), slot_aad(slot_type, header["vault_id"]))
    return header, dek, json.loads(AESGCM(dek).decrypt(nonce, ct, aad))


def create(pw, payload, hint=None, m=65536, t=3, p=4, device="python-ref"):
    vid, dek, rk = str(uuid.uuid4()), os.urandom(32), os.urandom(20)
    pkdf = {"alg": "argon2id", "m_kib": m, "t": t, "p": p, "salt": b64(os.urandom(16))}
    rkdf = {"alg": "hkdf-sha256", "salt": b64(os.urandom(16))}
    slots = []
    for typ, kdf, kek in (("password", pkdf, password_kek(pw, pkdf)), ("recovery", rkdf, recovery_kek(rk, rkdf))):
        n = os.urandom(12)
        slots.append({"type": typ, "kdf": kdf, "nonce": b64(n),
                      "wrapped_dek": b64(AESGCM(kek).encrypt(n, dek, slot_aad(typ, vid)))})
    header = {"v": 2, "vault_id": vid, "rev": 1, "updated": now(), "device": device}
    if hint:
        header["hint"] = hint
    header["slots"] = slots
    return encode(header, dek, payload), rk_display(rk)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create"); c.add_argument("out"); c.add_argument("password"); c.add_argument("--hint")
    c.add_argument("--m", type=int, default=65536); c.add_argument("--t", type=int, default=3); c.add_argument("--p", type=int, default=4)
    c.add_argument("--payload", help="JSON file with {entries:[...]}")
    o = sub.add_parser("open"); o.add_argument("file"); o.add_argument("password")
    r = sub.add_parser("recover"); r.add_argument("file"); r.add_argument("key")
    a = ap.parse_args()
    if a.cmd == "create":
        payload = json.load(open(a.payload, encoding="utf-8")) if a.payload else {"entries": [], "settings": {}}
        blob, rk = create(a.password, payload, a.hint, a.m, a.t, a.p)
        open(a.out, "wb").write(blob)
        print(rk)
    else:
        raw = open(a.file, "rb").read()
        if a.cmd == "open":
            _, _, payload = unlock(raw, lambda k: password_kek(a.password, k), "password")
        else:
            key = rk_parse(a.key)
            _, _, payload = unlock(raw, lambda k: recovery_kek(key, k), "recovery")
        json.dump(payload, sys.stdout, ensure_ascii=False, indent=2)
        print()


if __name__ == "__main__":
    main()
