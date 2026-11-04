# Task 13 for Claude Code: small UX polish + release 0.3.1

Owner decision (2026-11-04): do the UX fixes below plus a full-copy export, ship one new release, install it on the owner's phone over the existing app (data kept) and hand the APK over for the wife's phone. Multi-device sync is **dropped** (not planned).

Read first: `docs/HANDOFF.md` (permanent rule at the top), `docs/STATUS.md`, `docs/DECISIONS.md`.

Commit in small steps (code, then docs). Report to the owner at the end.

## Rules
- **Permanent rule:** the **release app** (`com.spirtosan.mobilevault`) on the owner's Xiaomi holds the owner's **real vault** `274cdb88…`. Never open, unlock, read, back up, restore over, clear, uninstall, re-pair or disconnect it, and never take screenshots of it. The **only** exception, used in Part C of this task: install the new release build **over** it with `adb install -r` (data kept). Never delete or touch `274cdb88…` on the server; the device `xiaomi` stays active.
- **Same protection for the wife's vault `85ed3926…` and her device `<WIFE_DEVICE>`**: never delete, trash, restore or revoke them. Her phone is not connected to this PC; don't try to reach it.
- All UI checks use the **debug app** (`com.spirtosan.mobilevault.debug`) with test data only.
- **No server change** in this task. Don't pair any device.
- **Public repo: placeholders only** (no people's names, no disk names); grep before every commit. Nothing from `C:\Users\spirt\mobilevault-release\` goes into git.
- Zero-knowledge stays: the server must never learn entry counts, names or anything else from inside a vault.

## Part A: the fixes
1. **Pairing screen** (`ui/BackupScreens.kt` ~line 179, test tag `pairCommand`): the line "On the server, run: mvault-admin pair <name for this phone>" is for the admin, not the user. In **release** builds replace it with plain text like "Ask the person who runs your backup server for a pairing QR code." Keep the command in debug builds (`BuildConfig.DEBUG`). Also check the other user-facing texts that mention `mvault-admin` (BackupError.kt line ~61, PairingPayload.kt messages, BackupScreens.kt ~112, ~163, ~276): in release, word them for a normal user ("Ask for a new pairing QR code", etc.); the admin command may stay in debug or in an "Advanced" detail line.
2. **Create-vault screen** (`ui/Screens.kt` ~line 209): "Unlock takes ~N s on this phone (Argon2id 64 MiB, t=3, p=4)". Show only "Unlock takes about N seconds on this phone." The Argon2 parameters go under a collapsed "Advanced" / "Details" row (or debug only; your choice, say which in the report). Don't change the parameters themselves.
3. **Restore vault list** (`VaultRow` in `ui/BackupScreens.kt` ~line 286): today the bold title is the short ID ("1a2b…, revision 42"). Make it easy for a non-technical user:
   - Title: **"Vault from <device>"** (fall back to the short ID if the device name is empty).
   - Second line: last backup date/time · number of versions · short ID (small, muted, still there so the owner can match it to the Emergency Kit).
   - Sort: most recent backup first.
   - Add one muted line above the list: "Pick the vault from your old phone. Your Emergency Kit shows its ID."
   - **No entry counts** (the server can't know them; zero-knowledge) and **no filtering by device** (a new phone has a new device name, so "own vaults only" would hide the right one). Only show what the server already returns.
4. **Fingerprint switch** (`ui/BiometricUi.kt` ~line 100): the reason text already exists (`biometricUnavailableReason`), but the owner saw only a greyed switch with no clear hint. Find out why on the owner's Xiaomi **using the debug app** (which `canAuthenticate(BIOMETRIC_STRONG)` result the phone returns; log it in debug). Then:
   - Make the reason clearly visible right under the switch (normal text colour, not faint).
   - For "none enrolled": "Set up a fingerprint in the phone's Settings → Fingerprint first, then come back here." Add a button "Open phone settings" (`Settings.ACTION_BIOMETRIC_ENROLL` on API 30+, else `ACTION_SECURITY_SETTINGS`), and re-check when the user returns to the screen (the `remember` must refresh on resume).
   - If the Xiaomi reports that the sensor isn't strong class (Class 3), say so in the report and **don't** fall back to weak biometrics (security decision; the owner decides later).

5. **Full-copy export + restore from a file** (new, owner decision 2026-11-04). Goal: a complete offline copy on the owner's USB that works **even if the server is gone**. Today "Export for desktop" drops notes, tags and favourites, and a fresh install can't open a Mobile Vault file (AppViewModel ~line 577 says "use Restore from backup").
   - **Settings → Import / Export → new button "Save a full copy"**: writes the current encrypted vault file (`vault.pvault`, PVAULT2, the exact bytes after the last save) via `CreateDocument`, default name `mobilevault-full-YYYY-MM-DD.pvault`. No new password: the copy opens with the **master password** (or the recovery key, if set). Everything included: sections, notes, tags, favourites. Say this in one muted line, plus: "Keep it on a USB stick or another safe place. Anyone needs your master password to open it." Before saving, make sure the vault is saved to disk (no unsaved edits).
   - **Welcome screen (no vault yet) → new button "Restore from a file"** under "Restore from backup": pick a `.pvault` file → ask for the master password or recovery key → check it opens → make it this phone's vault. Backup pairing is **not** carried over (the user pairs again if wanted). If the file is a desktop `.vault` (PVAULT1), say so and offer "Create new vault" then Import.
   - After restore-from-file, the vault keeps its **vault ID** (so a later backup continues the same vault on the server, if it still exists).
   - Tests: export → wipe debug app → restore from file → same entries, sections, notes, tags, favourites; wrong password; recovery key; a PVAULT1 file. **Debug app and test data only.** Never export the owner's real vault; the owner does that himself after the update.

## Part B: build and test
1. Bump to **versionName 0.3.1, versionCode 3**.
2. Unit tests and the existing UI tests pass. Add or adjust UI tests for the tags you touch (`pairCommand`, `vault:<id>`, `bioSwitch`).
3. On the owner's Xiaomi, **debug app only**: check each changed screen and the export/restore-from-file round trip. Restore list: check it with the debug test vaults on the server (if all test vaults are in trash and the list is empty, check with a fake list in a UI test instead and say so). Take screenshots **of the debug app only** for the report (`docs/screenshots/task13-*.png`, no real names in them).
4. Build the **release** APK with the same signing key as 0.3.0 (signer SHA-256 must start with `c8f34af6`). Check it with `apksigner verify --print-certs`.

## Part C: install and hand over
1. Copy to `C:\Users\spirt\mobilevault-release\MobileVault-0.3.1.apk` + `.sha256` (file SHA-256 and signer SHA-256), the same format as 0.3.0. Keep the 0.3.0 files there.
2. **Ask the owner for an explicit "go"**, then `adb install -r` the release APK on his Xiaomi (data kept). Don't open the app afterwards; the owner opens it himself and confirms he sees his entries (Personal 35 + Work 6).
3. Wife's phone: add a short note to `OWNER_STEPS.txt` (outside git): "Send her MobileVault-0.3.1.apk, she opens it and taps Update; data stays." `INSTALL.txt` step 7 already covers this; update the button names there if any changed.

## Part D: docs
Update `docs/STATUS.md`, `docs/HANDOFF.md` and `docs/DECISIONS.md` (multi-device sync dropped; restore list shows device + date, no filtering, and why). The next task number becomes **14** (planned: "install from zero" drill on the owner's phone). Add to the owner's to-dos: after 0.3.1, "Save a full copy" to the offline USB, and repeat now and then (e.g. monthly). Mention "Restore from a file" in `docs/BACKUP_AND_RECOVERY.md` and in the wife's `INSTALL.txt` (outside git). Report: what changed per fix (including the full-copy export), the fingerprint finding on the Xiaomi, test results, APK path + both SHA-256 values, and confirm that the release app was only updated with `adb install -r` and never opened.
