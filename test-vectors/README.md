# Test vectors

`sample-pvault1.bin` was produced by `tools/make_pvault1_sample.py`, which uses exactly the same algorithm as the desktop app.
(It's named `.bin` instead of `.vault` because `*.vault` is git-ignored on purpose.)

- Password: `correct horse battery staple`
- Expected plaintext: `sample-pvault1.expected.json`

The Android app's unit tests must decrypt this file and get identical JSON. They must also reject the file when given a wrong password.
