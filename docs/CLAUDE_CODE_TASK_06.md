# Task 06 for Claude Code: recovery-key fix, optional recovery key, 6-char master password, desktop-style UI

Owner feedback after the Xiaomi test (2026-11-02):
1. "Unlock with recovery key does not work."
2. Not sure he wants a recovery key at all → **decision: make it optional.**
3. Wants a shorter/simpler master password → **decision: minimum 6 characters**, with a warning, not a block.
4. Wants a friendlier, more colourful UI, closer to the desktop app (Shu PassVault).

The backup client (old "M3 / Task 06") moves to **Task 07**.
Read first: `docs/HANDOFF.md`, `docs/STATUS.md`, `android/README.md`. Rules from Task 05 still apply (no INTERNET permission, no Google Play deps, no secrets in the public repo, core-crypto changes need tests).

Do the parts in order and report to the owner after Part A and after Part D (screenshots or a short description per screen).

---

## Part A: find and fix the recovery-key bug

The owner's symptom was "something else" (not the "invalid key" or "does not open this vault" messages). **Start by asking him exactly what he saw**, then reproduce on the Xiaomi with a fresh test vault.

Cloud-session review found one likely cause:

- `Session.lock()` emits `lockEvents` **even when no vault is open** (by design, see the KDoc). `AppViewModel.onLocked()` then always does `replace(Unlock)`.
- So on the *Recovery key* screen (vault still locked), any of these throws the user back to the Unlock screen and **erases the typed key**:
  - switching to another app (e.g. the PDF viewer or Files to read the Emergency Kit) longer than the background grace period,
  - the screen turning off while slowly typing 36 characters.
- Same problem on Create (typed password lost) and Onboarding/import.

Fix:
- `onLocked()` must only navigate when something was actually open (a vault, a pending Emergency Kit, or imported data). If the app is already on a locked-side screen (Onboarding, Create, Unlock, Recovery), leave the screen and its typed text alone. Simplest: have `Session` report whether a vault was open, or check `kit`/`importBytes`/screen in `onLocked()`.

Also harden the Recovery screen (cheap, do it anyway):
- Single-line field, `KeyboardType.Ascii` + `KeyboardCapitalization.Characters`, `autoCorrect` off. Don't rewrite the value with `uppercase()` in `onValueChange` (some keyboards misbehave when the value is transformed while typing); `RecoveryKey.parse` already ignores case.
- Paste must work (the owner may copy it from a note/PDF).
- Show parse errors **inline under the field** (the exact `IllegalArgumentException` message, e.g. "got 35 characters"), not only as a snackbar.
- Show a progress indicator while unlocking.
- After a recovery unlock the forced "Set a new master password" screen must work: confirm `Save` becomes enabled (it waits for `vm.calibration`), and that leaving the app there doesn't leave a half state.

If the real cause turns out to be something else, fix that too and say what it was.

Add tests:
- core-crypto (if not already covered): create → `unlockWithRecoveryKey` with `display()` text, with lower case, with spaces instead of dashes, with O/I/L typos → opens; `changePassword` → recovery key still opens; `regenerateRecoveryKey` → old key fails, new key opens.
- App UI test: create vault → read the key from the `recoveryKey` tag → lock → Recovery → type key → set new password → lock → unlock with the new password. Plus: on Recovery screen, trigger `session.lock()` → screen and typed text stay.

---

## Part B: recovery key becomes optional

Format (`VAULT_FORMAT_V2.md`): a vault **may have no `recovery` slot**. Readers already look the slot up by type; make sure nothing assumes it exists.

core-crypto:
- `Pvault2.create(..., withRecoveryKey: Boolean = true)`: `Created.recoveryKey` becomes nullable.
- `UnlockedVault.regenerateRecoveryKey()` also works when there is no recovery slot (= "add recovery key").
- New `UnlockedVault.removeRecoveryKey()` (drop the slot). Call `seal` afterwards as usual.
- `VaultHeader.hasRecoveryKey` helper. Tests for all of this, including: a vault without recovery slot opens with the password, `unlockWithRecoveryKey` gives a clear error, export to PVAULT1 still works.

App:
- **Create flow:** after the password step, a "Recovery key" step:
  - Text: "A recovery key is a 36-character code you print or write down. If you forget your master password, it is the **only** way back in. Server backups won't help: they are locked with the same password."
  - Buttons: **"Create recovery key (recommended)"** (orange) → existing Emergency Kit screen; **"Skip"** (plain) → confirm dialog "Without a recovery key, a forgotten master password means all passwords in this vault are lost. Skip anyway?" → create without it.
  - Same for "create new vault from desktop import".
- **Unlock screen:** show "Forgot password? Use recovery key" only when the header has a recovery slot (use `peekHeader`). Otherwise show nothing (no dead end).
- **Settings → Recovery key** section:
  - No key: "No recovery key. If you forget the master password, the vault is lost." (orange) + button "Create recovery key" → Emergency Kit.
  - Has key: "New recovery key" (existing regenerate flow) and "Remove recovery key" (red) → ask for the master password, confirm dialog, then remove.
- Emergency Kit PDF text unchanged.

Update `DECISIONS.md` (recovery key optional, owner's choice 2026-11-02), `ANDROID_APP_SPEC.md`, `BACKUP_AND_RECOVERY.md`.

---

## Part C: master password minimum 6 characters

- `MIN_MASTER_LENGTH = 6` (was 8). Applies to create and change password.
- Under 6: "Use at least 6 characters." and Save disabled (as now).
- 6+ but strength meter below "Fair" (level < 2) **or** shorter than 10: orange note, Save still enabled:
  "Short passwords are easier to guess if someone copies the vault file or a backup. Fine for daily use if your phone has a screen lock." Keep it one or two lines.
- Don't change Argon2 settings. Hint policy unchanged (the 4-char substring check still works with 6-char passwords).
- Update `DECISIONS.md` + spec.

---

## Part D: friendlier, desktop-style UI

Goal: it should feel like the desktop app (`reference/desktop-passvault/screenshots/main_screen.png`, palette `C` in `password_manager.py`): dark background with **clearly coloured action buttons**, orange brand title. Dark theme only.

Add to `Palette` the missing desktop colours: `green #27AE60`, `teal #16A085`, `steel #2471A3`, `purple #7D3C98`, `amber #B7950B`, `violet #6C3483`, `bgHover #3A3A3A`, `bgStatus #161616`, `navBg #1E3A5F`, `navFg #C8DDF0`.

Use the desktop's colour meaning everywhere:
| Action | Colour | Text |
|---|---|---|
| Main action (Unlock, Create vault, Save, Open) | orange | dark `#1A1A1A`, bold |
| Add entry, FAB "+" | green | white |
| Edit | blue | white |
| Delete, Lock, Remove recovery key | red | white |
| Copy username | teal | white |
| Copy password | steel | white |
| Copy URL / open | purple | white |
| Show/hide password | amber | white |
| New vault / import | violet | white |
| Secondary (Cancel, Skip) | `bgHover` with `fgSecondary` text | |

Buttons: rounded 8–10 dp, small leading icon (like the desktop), height ≥ 48 dp.

Screens:
- **Unlock / Onboarding / Create:** centred app icon, "MOBILE VAULT" in orange bold (like the desktop sidebar title), card (`bgPanel`, rounded) holding the fields. Orange Unlock button.
- **Top bar:** `bgToolbar`, title "Mobile Vault" in orange; Lock icon in red, Settings in `fgSecondary`.
- **List:** search field in `bgInput` with search icon. Each row: round coloured initial (colour picked from the desktop accent set by hash of the service name), service in `fgPrimary` bold, username in `fgSecondary`, orange star for favourites. Thin `sep` dividers. Pressed row uses `selectBg`. Small status line at the bottom like the desktop (`bgStatus`, `fgMuted`): "12 entries • AES-256-GCM • Argon2id". Green FAB.
- **Entry detail:** each field in a `bgPanel` card: label (`fgSecondary`, small), value, and a coloured copy button per the table (teal/steel/purple, amber eye). Edit (blue) and Delete (red) at the bottom.
- **Edit / generator:** inputs `bgInput`; strength meter colours already match desktop; generate button orange.
- **Settings:** grouped cards with section headers in orange (Security, Recovery key, Backup [coming soon], Import/Export, About).
- Snackbars: `bgPanel` background; success green accent, errors red.
- Dialogs: `bgPanel`, confirm buttons coloured by meaning (red for destructive).

Check: edge-to-edge insets on the Xiaomi, text contrast (white on amber/teal is OK at button size; keep labels bold), large font scale (1.3×) doesn't clip buttons, Huawei Android 10 looks the same.

Show the owner the result: FLAG_SECURE blocks screenshots, so either add a **debug-build-only** switch that turns FLAG_SECURE off (never in release), then `adb exec-out screencap`, or ask the owner to look on the phone. Iterate once on his comments.

---

## Done when
- All core-crypto and app tests pass (old + new), lint and release build pass, merged manifest still has no INTERNET permission.
- On the Xiaomi: recovery-key unlock works including switching to the PDF viewer mid-typing; vault without recovery key works end to end; 6-char password accepted with the warning; owner OK with the new look.
- Re-run the remaining Task 05 checklist items 4–13 on the Xiaomi with the new build.
- Commit + push in logical commits (A, B, C, D). Update `STATUS.md`, `android/README.md`, `HANDOFF.md` ("Next": Task 07 = backup client, biometric unlock, reminders).
