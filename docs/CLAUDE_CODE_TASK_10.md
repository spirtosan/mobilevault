# Task 10 for Claude Code: sections (Personal / Work) + import a vault into a section

Owner decision (2026-11-03): **one vault with sections**, not several vaults. One master password, one recovery key, one backup. The owner has two existing vaults (personal and work) that should each go into their own section. The rule is "easy to use": no extra steps for people who don't use sections.

Do this **after Task 09** is finished. Read first: `docs/HANDOFF.md`, `docs/STATUS.md`, `docs/VAULT_FORMAT_V2.md`, `docs/ANDROID_APP_SPEC.md`, `core-crypto/.../VaultPayload.kt`, `data/VaultEdits.kt`, the import flow in `AppViewModel` / `Screens.kt`.

Commit each part separately. Report to the owner at the end.

## Rules
- Same as before: Xiaomi only, no Google Play deps, minSdk 28. **Public repo: placeholders only**; grep before every commit.
- No server changes. The vault format stays PVAULT2 v2; this only adds optional payload fields. Old files must open unchanged, and files written by older app versions or `android/tools/pvault2.py` must keep their unknown fields (round-trip test).
- The test vault (password in the chat) may be used for all tests.

---

## Part A: sections in the data model

1. **Entry:** a new optional field `section` (string id, empty = no section) in the payload JSON, key `"sec"`. It is separate from `tags` (tags stay free-form labels).
2. **Section list:** `payload.settings["sections"]` = ordered list of `{"id": "<uuid>", "name": "Work"}`, so empty sections and their order survive. An entry whose `sec` isn't in the list is shown under "No section" (robust against hand-edited files).
3. Pure functions in `VaultEdits`: add, rename, reorder sections; delete a section (allowed when empty, or with "move its entries to …" / "to No section"; **never** deletes entries); move entries; `visible(…)` gets a section filter.
4. Update `docs/VAULT_FORMAT_V2.md` (payload fields `sec`, `settings.sections`) and `android/tools/pvault2.py` if it prints entries (show the section name).
5. Unit tests: round trip with and without sections, unknown section id, delete with move, old files without the fields.

## Part B: sections in the app

1. **Vault list:** if the vault has **no sections**, nothing changes (no chips, no extra UI). With sections, a chip row at the top: **All · Personal · Work · …** (horizontally scrollable, remembers the last choice). Search always looks across **all** sections; results show a small section label when "All" is selected.
2. **New entry:** defaults to the selected section (or no section under "All"). The entry editor gets a **Section** dropdown (including "No section"). Entry detail shows the section.
3. **Settings → Sections:** add, rename, reorder, delete (with the move choice above). First-time hint on the empty list menu: "Split into sections (e.g. Personal / Work)" → creates the first two sections and offers to move all current entries into the first one.
4. **Export for desktop:** a new choice "All entries / only section X". The desktop app has no sections, so exporting a section gives a separate desktop file (e.g. a "work" file).
5. UI tests: create sections, add an entry into "Work", filter by chip, move an entry, delete a section with a move. Check on the Xiaomi with screenshots in the debug app (test data only).

## Part C: import a vault into a section

1. **Import sources** (Settings → Import):
   - **Desktop vault** (PVAULT1, the existing flow, password of that file);
   - **Mobile Vault file** (PVAULT2, e.g. an older vault or a copy from another phone; opened with **that** file's master password or recovery key, decrypted in memory only, never saved as a vault);
   - **From the backup server**: pick another vault from `GET /vaults` (not the open one), pick a revision (default: newest), then unlock with that vault's password or recovery key, as above.
2. **Import preview:** "The file contains N entries." Then **"Import into section:"** [existing sections ▾ / **New section…** (name prefilled from the file name or "Imported")/ No section]. Merge as today (skip exact duplicates of service + username + password *within the target section*), keep notes, tags and favourites from PVAULT2 sources, and give imported entries new ids.
3. If the imported PVAULT2 vault itself has sections: offer "Keep its sections" (they are added, with a suffix on name clashes) or "Put everything into one section".
4. After a successful import: "Imported N entries into Work (skipped M duplicates)". It is saved and backed up like any change. The source file or server vault is **not** touched or deleted. Tell the owner he can keep or delete the old vault himself (on the server: retention prunes it later, or leave it).
5. Onboarding stays as is (create, import as new vault, restore). Importing into sections needs an open vault.
6. Tests: unit tests for merge into a section, duplicate rules, section clash. Live: import the test vault from the server into a new section "Work-test" of the debug app's vault, then a desktop sample file (`tools/make_pvault1_sample.py`) into another section.

## Part D: finish
- All tests, lint 0, release build installed over the existing one (keeping data). Desktop export is still byte-compatible (open the export with the desktop format reader in tests).
- Update `docs/STATUS.md`, `docs/HANDOFF.md`, `docs/DECISIONS.md` (sections in one vault instead of multiple vaults; `sec` field; imports into sections).
- Report with a **short how-to for the owner**: "Settings → Sections → create Personal and Work → Settings → Import → pick each old vault → choose its section".
