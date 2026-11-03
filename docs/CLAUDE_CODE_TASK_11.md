# Task 11 for Claude Code: safe vault deletion on the server + the release app becomes the owner's real vault + import fixes

Owner decisions (2026-11-03):
- Test vaults uploaded by debug pairings clutter the restore and import lists. They should be removable, safely.
- The owner is about to turn the release app's test vault into his **real vault** (new strong password, DEK rotation, real entries). From the moment this task is done, the release app is **off-limits** for Claude Code.

Read first: `docs/HANDOFF.md`, `docs/STATUS.md`, `server/README.md`, `server/mvault/admin.py`, `server/mvault/common.py` (storage layout, retention).

Commit each part separately. Report to the owner at the end.

## Rules
- **Server change approved for Part A only** (pasting this task = approval). Back up first (as in Task 08), deploy to `/opt/mvault/`, run the unit tests and the smoke test.
- **Public repo: placeholders only**; grep before every commit.
- **New permanent rule (put it at the top of `docs/HANDOFF.md`, in `docs/DECISIONS.md` and in every future task's Rules):** the **release app** (`com.spirtosan.mobilevault`) holds the owner's **real vault**. Never open, unlock, read, back up, restore over, clear, uninstall, re-pair or disconnect it, and never take screenshots of it. The only exception is installing a newer release build **over** it, which keeps its data. All tests use the **debug app** (`com.spirtosan.mobilevault.debug`) with test data only. The old test password is retired; don't use it on the release app.
- The server device `xiaomi` (the release app) must stay active and untouched. The owner's **real vault is `274cdb88…`**: never delete, trash, restore or read it.

---

## Part A: `mvault-admin delete-vault` / `undelete-vault` / `trash`

1. `mvault-admin delete-vault <vault-id>`:
   - shows the vault (number of revisions, newest rev and date, the device of the last upload) and asks to **type the first 8 characters of the ID** to confirm (`--yes` skips this, for scripts and tests);
   - **moves** the vault's files into a root-only trash folder next to the archive (e.g. `<data>/trash/<vault-id>-<UTC timestamp>/`); nothing is deleted at this point;
   - after the move, the API no longer lists or serves the vault (`GET /vaults`, `/revs`, `/latest` → not there / 404). A later upload with the same ID starts fresh; it does not resurrect the trash.
2. `mvault-admin undelete-vault <vault-id>`: moves the newest trash copy back. Refuse if the vault exists again (print what to do).
3. `mvault-admin trash`: lists trashed vaults with their deletion date and when they will be purged.
4. Purge: the existing **retention timer** also permanently deletes trash entries older than **30 days**. Log what it deletes.
5. Keep the append-only guarantee for the phone: the API still has **no** delete; only root on the server can do this.
6. Tests in `server/tests/` (delete → hidden from the API → undelete → visible again; purge after 30 days with a faked clock; wrong confirmation → nothing happens). Update `server/README.md` (admin commands) and `docs/BACKUP_AND_RECOVERY.md` (how deleting works and why it's recoverable for 30 days).
7. Deploy, smoke test.

## Part B: clean up the debug test vaults

1. Run `mvault-admin stats` and list all vaults with their last device.
2. Delete (with the new command) **only** vaults whose uploads all came from revoked **debug/test** devices (e.g. `xiaomi-debug*`, `smoketest*`, `RamboPhone`, `xiaomi-qr`). Expected: `c3690f79-…`, `dccf07c8-…`, `baa39212-…`. **Never** delete `274cdb88…` (the owner's real vault, uploaded by the release device `xiaomi`) or anything else `xiaomi` uploaded. If anything is unclear, list it and ask the owner instead.
3. Make the smoke test and future debug pairing tests clean up after themselves: at the end, `delete-vault --yes` for any vault they uploaded (document this in `docs/HANDOFF.md` "How we test").

## Part C: hand the release app over to the owner

1. Apply the new permanent rule (see Rules) in `docs/HANDOFF.md`, `docs/DECISIONS.md` and `docs/STATUS.md`. Replace every "test vault in the release app" statement in the docs.
2. Remove the old test password from anything outside git that you created (shell history, scratch files), if any.
3. Report: what changed, test results, which vaults were moved to the trash (with purge dates), the remaining devices, and confirm that the release app was not touched.

## Part D: two import bugs (found by the owner during the real import)

Test everything on the **debug app** with test vaults only.

1. **Section name clash, silent failure.** In "Import into a section", the preselected choice is "New section" with a name that can already exist (e.g. "Personal"). Importing then fails without any message.
   - Fix: if a section with the suggested name already exists, preselect that **existing** section instead of "New section".
   - If the user still types a new name that already exists, show a clear error ("A section called X already exists — pick it from the list or use another name") and don't continue.
   - Any import failure must show a message; no silent failures anywhere in the import flow.
2. **Password field not empty.** The import password field once showed 5 dots before the owner typed anything.
   - Find the cause (state kept between screens, saved state restore, autofill, a default value).
   - Check **every** password field in the app (unlock, create vault, change password, import, export, restore, recovery key) starts empty every time it opens, and is cleared after use and when the screen closes. Turn off autofill for these fields if it is the cause.
3. Unit/UI tests for both bugs where practical.
4. Build the release APK (same signing key, bump `versionCode`/`versionName`) and **install it over** the release app (`adb install -r`, data kept). Do not open the release app after installing. Tell the owner to open it and check that his vault still unlocks and shows his entries.

