# Task 05 for Claude Code: M2, Android app MVP

Implement the `app` module on top of `android/core-crypto` (done, 27 tests). **You** write and build the app code here, because this PC has Android Studio and the test phones. The cloud session reviews the result.
Read first: `docs/HANDOFF.md`, `docs/ANDROID_APP_SPEC.md`, `docs/VAULT_FORMAT_V2.md`, `android/README.md`.

## Test devices (owner's phones)
| Phone | Android | API | Notes |
|---|---|---|---|
| Huawei | 10 | 29 | **Probably no Google Play services (HMS).** The app must not use any `com.google.android.gms` / Play / ML Kit / Firebase dependency. Aggressive battery manager. Face unlock is usually weak (class 2), so only `BIOMETRIC_STRONG` counts (M3). Likely the slowest device; it sets the Argon2 budget. |
| Motorola | 12 | 31 | Near-stock baseline. |
| Xiaomi | 15 | 35 | HyperOS/MIUI autostart and battery restrictions. Target SDK 35 means **edge-to-edge is enforced** (handle insets). 16 KB pages: no native libs (BouncyCastle is pure Java, so it's fine). |

The owner enables Developer options → USB debugging on each phone, and you install with `adb` (ask the owner to connect the phones one at a time).

## Rules
- `minSdk 28`, `targetSdk 35`, `compileSdk 35`. Kotlin 2.0.x, Compose BOM (current stable), Material 3. AndroidX only. **No Google Play services dependencies** (QR scanning in M3 will use ZXing, not ML Kit).
- **No `INTERNET` permission in M2.** Check the merged manifest.
- No secrets, real domains or IPs in code/commits (public repo).
- Keep `core-crypto` unchanged unless a bug is found. If you change it, add a test, and all tests must still pass.

## Step 1: build pipeline + Argon2 benchmark (report before continuing)
1. Add the `app` module (`com.spirtosan.mobilevault`), `include(":app")`, `implementation(project(":core-crypto"))`. Use the app icon from `assets/icon.png` (adaptive icon).
2. Add a debug-only "Crypto benchmark" screen. It times `Pvault2.create` + `unlockWithPassword` with (64 MiB, t=3), (64 MiB, t=2), (32 MiB, t=3) and (32 MiB, t=2), 3 runs each, plus `Pvault1.decrypt` of the desktop sample (600k PBKDF2).
3. Run it on **all three phones**. Report a table of median ms per unlock (create+unlock / 2). Also check that none of them hits OutOfMemory at 64 MiB.
4. **Parameter rule:** at vault creation, use the strongest of those 4 settings whose unlock is ≤ 1000 ms on the **current** phone, never below 32 MiB/t=2. Show "Unlock takes ~X s on this phone" in the create screen. A vault created on the Xiaomi must still open on the Huawei within ~3 s; if it doesn't, tell the owner and cap creation at the Huawei-safe setting.

## Step 2: the app (M2 scope)
**Storage**
- Vault at `filesDir/vault.pvault`. Atomic save: write `vault.pvault.tmp`, `fsync`, rename.
- Snapshots in `filesDir/snapshots/`: keep the last 20, plus 1 per day for 30 days.
- Never write plaintext to disk. No logging of entries/passwords (strip `Log` calls in release via R8 rules).

**Screens** (Compose, dark theme using the desktop palette from `reference/desktop-passvault/password_manager.py`):
1. **Onboarding:** "Create new vault" / "Import from desktop (.vault)". ("Restore from server" is shown disabled, labelled "coming soon".)
2. **Create vault:** master password + confirm, strength meter (port `score_password` from desktop or better), optional hint with live `HintPolicy` check and a warning that the hint is stored unencrypted. Then:
3. **Emergency Kit:** shows the recovery key (`RecoveryKey.display()`) once, with instructions to write it down or print it. The user must re-type 3 randomly chosen groups to continue. Offer "Save Emergency Kit as PDF" (Android `PdfDocument` → SAF `CreateDocument`; the PDF contains the key + vault ID + short restore instructions). Then wipe the key.
4. **Unlock:** password field. After the first failure, a "Show hint" button. "Forgot password? Use recovery key" leads to a recovery-key input, then forces setting a new master password. Run Argon2 off the main thread with a progress indicator. Increasing delay after 5 failures.
5. **Vault list:** search (service / username / URL), sort A–Z / recently modified, favourites first. Tap → detail. FAB "+".
6. **Entry detail/edit:** service*, username, password* (show/hide, generator: length 12–64 + character sets + passphrase option), URL, notes, favourite. Copy buttons. Delete with confirmation (it's a tombstone: `deleted=true`, `modified=now`).
7. **Settings:** auto-lock timeout (1/2/5/10/30 min, default 5), lock on leaving the app (immediately / after 30 s / 1 min), clipboard clear (10/30/60 s/never, default 30), change master password (+ hint), regenerate recovery key (→ Emergency Kit again), export for desktop (PVAULT1 via SAF, with a warning that notes/tags are dropped), about (version, GPL-3.0, GitHub link).

**Import from desktop:** SAF `OpenDocument` → `Pvault1.decrypt(desktop password)` → preview the count → choose: new vault (then the create flow) or merge into the current vault (skip exact duplicates by service+username+password).

**Security behaviour**
- `FLAG_SECURE` on the activity.
- Lock on: the idle timeout, the app going to the background (after the grace period), screen off (`ACTION_SCREEN_OFF`). Locking calls `UnlockedVault.wipe()`, clears view-model state and navigates to Unlock.
- Clipboard: `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+. Clear after the timeout **and** on lock. Verify that clearing works on Android 10 when the app is in the background; if not, clear on lock/resume and document it.
- Password fields: `KeyboardType.Password`, autocorrect off, `IME_FLAG_NO_PERSONALIZED_LEARNING` (via `imeOptions` interop or a custom `TextField` config).
- Manifest: `allowBackup="false"`, `dataExtractionRules` excluding everything, `android:hasFragileUserData="false"`, no INTERNET.
- Release build: R8 on, `debuggable false`. Keep debug builds clearly marked.

**Persistence of settings:** DataStore (non-secret values only).

## Step 3: tests + checks
- Unit tests for the repository (atomic save, snapshot retention, tombstone delete, import merge), using a temp dir.
- Compose UI test (or instrumented) for create → lock → unlock → add entry → lock → unlock shows the entry, at least on the Motorola.
- Manual checklist on **each phone** (report ✓/✗ per phone): create; Emergency Kit PDF saved; unlock; wrong password + hint; recovery-key unlock + new password; add/edit/delete/search; copy password, then clipboard cleared after the timeout; auto-lock after idle; lock when switching apps; screenshot blocked; desktop import of `test-vectors/sample-pvault1.bin` (password `correct horse battery staple`); export for desktop opens in the desktop app; airplane mode makes no difference (no network use).
- `./gradlew :core-crypto:test :app:testDebugUnitTest :app:lintDebug assembleRelease` all green. Report the APK size.

## Output
Commit in logical steps (pipeline + benchmark, then app, then tests) and push. Update `docs/STATUS.md` (M2 done or what's left), the Argon2 table in `docs/DECISIONS.md`, and `android/README.md`. Report results, the per-phone checklist, and anything you decided differently from this task, with the reason.
