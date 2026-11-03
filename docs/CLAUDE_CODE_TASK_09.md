# Task 09 for Claude Code: simpler Emergency Kit step + one-click phone pairing

Owner feedback (2026-11-03): retyping 3 groups of the recovery key is too much work, and people would just skip it. Pairing should be easier. The fingerprint checks are skipped for now and only reopened if a problem shows up.

Read first: `docs/HANDOFF.md`, `docs/STATUS.md`, `docs/ANDROID_APP_SPEC.md` (Emergency Kit section), `server/mvault/admin.py` (`pair`).

Commit each part separately. Report to the owner at the end.

## Rules
- Same as Task 08: Xiaomi only, no Google Play deps, minSdk 28. **Public repo: placeholders only** (no domain, IP, user name, key paths or passwords in the repo; grep before every commit).
- **No server code or config changes** in this task.
- The release app's vault is the test vault (the password is in the chat). You may use it for tests.

---

## Part A: Emergency Kit without the typing test

1. Remove the 3-group challenge from the Emergency Kit screen (`KitScreen`, `KitState.challenge`, `confirmKit(typed)`).
2. The screen keeps: the key, the Vault ID, **"Save Emergency Kit as PDF"**, and a new button **"I wrote it down"**.
   - A successful PDF save marks the kit as done (the same as today's confirm: `kitPending = false`, `kitChecked = now`, key wiped, go on). Show "Emergency Kit saved" and continue automatically.
   - "I wrote it down" does the same with one tap.
   - If the PDF picker is cancelled, nothing changes and the user stays on the screen.
3. Leaving without either (lock, back, app killed) keeps the existing **"Recovery key not confirmed"** card. Reword it: "You didn't save or write down your recovery key. Make a new one, or remove it." Keep its buttons.
4. **New, optional:** Settings → Security → **"Check my recovery key"**. Type or paste the full key → "✓ This key opens your vault" or "✗ This key doesn't open this vault". It only checks (try-unlock in memory with `Pvault2.unlockWithRecoveryKey` on the current file, then wipe). It never changes anything and needs no master password. It is throttled like password unlock.
5. Update `docs/ANDROID_APP_SPEC.md` (Emergency Kit) and the tests (unit + UI test for the kit step: PDF path and "I wrote it down" path). Live on the Xiaomi: create a recovery key in the debug app → "I wrote it down" → no pending card; then "Check my recovery key" with the right and a wrong key.

## Part B: one-click pairing from the owner's PC

Goal: the owner double-clicks one file, types a device name, and the pairing QR appears full screen on the PC monitor, ready to scan with the phone.

1. Add `tools/windows/Pair phone.cmd`, which runs `tools/windows/pair-phone.ps1` (PowerShell 5.1, which ships with Windows; uses the built-in OpenSSH client). It:
   - reads the server connection settings from a **local config outside the repo**: `%USERPROFILE%\mobilevault-secrets\pair-phone.json` (`{"ssh": "<user>@<host>", "port": 22, "identity": "<optional key file>"}`). If the file is missing, it explains what to put there and stops. Add `tools/windows/pair-phone.example.json` with placeholders only.
   - asks for a device name (default `phone-YYYYMMDD`), validates it like `mvault-admin` does.
   - runs `mvault-admin pair <name>` over SSH, takes the printed `Payload:` line, and renders the QR **on the PC** (no file left on the server). The simplest way: run `qrencode -o - -t PNG -s 10 '<payload>'` over SSH and save its output to a temp PNG under `%TEMP%`. If PowerShell piping of binary output is a problem, do it another way, but keep nothing on the server.
   - opens a simple full-screen window with the QR (WinForms `PictureBox`, white background), the device name, a 10-minute countdown and the code text below it (for the "paste" fallback).
   - deletes the PNG when the window closes or the 10 minutes run out, whichever comes first.
   - ends with: "Scanned? On the phone: Settings → Backup → Test connection."
2. Use the SSH setup you already use for the server (key or password prompt in the console, the owner's choice). Don't store any password.
3. Add a short "Pair a new phone" section to `server/README.md` and `docs/BACKUP_AND_RECOVERY.md`: double-click → name → scan → Test connection; on the old phone, or if it was lost: `mvault-admin revoke <old-name>`. The fallback stays SSH + `mvault-admin pair` + paste.
4. Test with the owner: run the script, the owner scans with the **debug** app, Test connection OK, then disconnect the debug app and `mvault-admin revoke` that name. Check that the PNG is gone afterwards.

## Part C: finish
- All tests, lint 0, release build installed over the existing one (keeping data).
- Update `docs/STATUS.md` and `docs/HANDOFF.md`: Task 09 done; the fingerprint checks are skipped by the owner's decision (reopen only if a problem shows up).
- Report: what changed, test results, the remaining devices on the server, and how the owner pairs a phone from now on (one line).
