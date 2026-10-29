"""Generate a PVAULT1 sample vault identical in format to Shu PassVault desktop v1.3.
Usage: python make_pvault1_sample.py <out.bin> <expected.json>"""
import os, sys, json
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"PVAULT1\x00"
PASSWORD = "correct horse battery staple"
DATA = {"entries": [
    {"s": "GitHub", "u": "spirtosan", "p": "gh-Pa$$w0rd!", "url": "https://github.com"},
    {"s": "Bank", "u": "user@example.com", "p": "Žluťoučký kůň 🐎 / Кирилица", "url": ""},
    {"s": "WiFi", "u": "", "p": "x", "url": ""},
]}

def derive(pw, salt):
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt,
                      iterations=600_000).derive(pw.encode("utf-8"))

salt, nonce = os.urandom(16), os.urandom(12)
blob = MAGIC + salt + nonce + AESGCM(derive(PASSWORD, salt)).encrypt(
    nonce, json.dumps(DATA).encode("utf-8"), None)
# self-check
raw = blob
assert json.loads(AESGCM(derive(PASSWORD, raw[8:24])).decrypt(raw[24:36], raw[36:], None)) == DATA
open(sys.argv[1], "wb").write(blob)
open(sys.argv[2], "w", encoding="utf-8").write(json.dumps(DATA, ensure_ascii=False, indent=2))
print("ok", len(blob), "bytes")
