# Task 12 for Claude Code: release APK + install guide for the owner's wife

Owner decisions (2026-11-03):
- The owner's wife gets Mobile Vault on her own phone, with **her own vault** (her own master password and recovery kit), backed up to the same server.
- She installs it herself from an APK file the owner gives her, following a plain-language guide. The guide is in **English** (the app is English only, so button names match the screen).

Read first: `docs/HANDOFF.md` (permanent rule at the top), `docs/BACKUP_AND_RECOVERY.md`, `tools/windows/Pair phone.cmd` + `pair-phone.ps1`, the app's screens for create vault / recovery kit / "Back up to your server".

Commit separately (docs only; the APK and the guide folder are **outside** the repo). Report to the owner at the end.

## Rules
- **Permanent rule:** the **release app** (`com.spirtosan.mobilevault`) on the owner's Xiaomi holds the owner's **real vault** `274cdb88…`. Never open, unlock, read, back up, restore over, clear, uninstall, re-pair or disconnect it, and never take screenshots of it. The only exception is installing a newer release build over it (data kept); **not needed in this task**. All tests use the **debug app** with test data only. Never delete or touch `274cdb88…` on the server; the device `xiaomi` stays active.
- **No server change** in this task. Don't pair any device; the owner pairs her phone himself.
- **Public repo: placeholders only**; grep before every commit. Nothing from `C:\Users\spirt\mobilevault-release\` goes into git.

## Part A: the APK
1. Use the **0.3.0 (versionCode 2)** release build from Task 11 (the one installed on the owner's phone). If the file is no longer there, rebuild the same commit with the same signing key; don't bump the version.
2. Check it with `apksigner verify --print-certs`: signer certificate SHA-256 must start with `c8f34af6`; package `com.spirtosan.mobilevault`; versionName 0.3.0.
3. Copy it to `C:\Users\spirt\mobilevault-release\MobileVault-0.3.0.apk` and write `MobileVault-0.3.0.apk.sha256` (file SHA-256, plus the signer certificate SHA-256 on a second line).

## Part B: `INSTALL.txt` for her (in the same folder)
Plain language, short numbered steps, no technical words (no "APK signing", "mTLS", "DEK"). Use the **exact** button and screen names from the app's code. Cover:
1. **Install:** open the file on the phone; allow "install unknown apps" for the app she opens it from (Files / browser / messenger), once. If the phone warns about an unknown app, that's expected.
2. **Create new vault:** choose her own master password (at least 6 characters; a sentence she'll remember is best). Nobody else needs to know it, and the owner can't recover it for her.
3. **Recovery kit:** save the kit PDF, then either print it or give the file to the owner to keep on the offline USB. Explain in one sentence why: it's the only way back in if she forgets the password.
4. **Back up to your server:** tap it, choose Scan QR, and **at this moment** the owner runs `Pair phone.cmd` on his PC with the device name `wife` and shows her the QR code. After pairing, check that the backup indicator turns green.
5. **Keep it running:** battery "No restrictions" and Autostart on. Give the Xiaomi/HyperOS steps and one generic Android line (her phone model may differ).
6. **Fingerprint** (optional): how to turn it on in the app's settings.
7. **Updates:** the owner sends a new file → open it → "Update". Data stays.
8. **Never uninstall the app** and never "Clear data". If the phone is lost or broken: install on the new phone → "Restore from backup" → the owner pairs it again → pick **her** vault → type her password.
9. If she ever sees another vault in a list (the owner's), ignore it; she can't open it and doesn't need to.
10. Short "Something went wrong?" section: who to ask (the owner), what to tell him (a screenshot of the error, never her password).

Also write `OWNER_STEPS.txt` (same folder, for the owner): how to run `Pair phone.cmd` for her (device name `wife`, what the script asks, how long the QR is valid), how to check on the server that her first upload arrived (a read-only `mvault-admin` command he runs, or the command for Claude Code to run), and a reminder to put her kit PDF on the offline USB.

## Part C: check the guide on the debug app
1. On the owner's Xiaomi, follow `INSTALL.txt` steps 2–3 and 6 **on the debug app** (`com.spirtosan.mobilevault.debug`) with a throwaway test vault, and make sure every button name and screen in the guide matches. **Don't pair**: stop before "Back up to your server" → Scan QR (only check the screen names). Afterwards delete the throwaway test vault data from the debug app (it never reached the server).
2. Fix the guide where it doesn't match. If an app screen is confusing for a non-technical user, list it in the report (don't change the app in this task).

## Part D: docs
Update `docs/STATUS.md` and `docs/HANDOFF.md` (wife's phone: APK + guide ready, waiting for the owner to install it with her). Report: file paths, both SHA-256 values, which guide steps were checked on the debug app and what was fixed, and confirm the release app was not touched.
