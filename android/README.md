# Mobile Vault: Android project

| Module | Status | |
|---|---|---|
| `core-crypto` | ✅ M1 + Task 06 | Pure Kotlin/JVM. PVAULT1 import/export (desktop), PVAULT2 vault format with a password slot and an **optional** recovery-key slot, own Argon2id (p=4 lanes), recovery key, password-hint policy, entry model. 39 tests |
| `app` | ✅ M2 (Task 05) + Task 06 + M3 (Task 07) | Jetpack Compose, desktop-style dark UI. Create (recovery key optional, backup step) / unlock (password or fingerprint) / recovery / list / detail / edit / generator / settings, auto-lock, clipboard clear, desktop import/export, FLAG_SECURE. Backups to the own server (pairing, uploads, health chip, restore, version history, test restore), password reminders. INTERNET only for backups. 51 unit tests + 9 UI tests |

## Build & test
Requirements: JDK 17+. Android Studio's bundled JDK works: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` (Java is not on PATH).
```
./gradlew :core-crypto:test                 # crypto, PC only
./gradlew :app:testDebugUnitTest            # app logic (policy, edits, merge, snapshots, time format)
./gradlew :app:connectedDebugAndroidTest    # UI flows on a connected phone (debug app id, own data)
./gradlew :app:lintRelease :app:assembleRelease
```
- UI tests (`app/src/androidTest/.../VaultFlowTest.kt`) cover: create → lock → unlock → add entry; recovery key → new password; a lock on the Recovery / Create / Emergency Kit screen; skip / add / remove the recovery key; 5 vs 6 character master password. They wipe the **debug** app's vault (`com.spirtosan.mobilevault.debug`), never the release app's. Gradle uninstalls the debug app after the run.
- Release APK: the `release` build type has no signing config. For test installs it is signed by hand with the local debug keystore, so updates keep the data:
  ```
  zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
  apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out signed.apk aligned.apk
  adb install -r signed.apk          # same certificate as the installed app → vault kept
  ```
  Check the certificate first (`apksigner verify --print-certs`). Installing with a different key requires an uninstall, which **deletes the vault**.
- Backup tests over adb: on the server `mvault-admin pair <name>`, then paste the `Payload:` JSON into Settings → Backup → Connect → "Pairing text" (`adb shell input text '…'`; the field uses a URI keyboard so nothing gets auto-capitalised). Check with `mvault-admin list` / `stats`. Revoke test devices afterwards. Debug builds show a "Debug: reminder clock" card in Settings to fast-forward the reminders and the fingerprint N-day rule, and expose test tags to `uiautomator dump` as resource ids.
- Screenshots: FLAG_SECURE blocks them. **Debug builds only**: `adb shell am start -n com.spirtosan.mobilevault.debug/com.spirtosan.mobilevault.MainActivity --ez debug_allow_screenshots true`, then `adb exec-out screencap -p > x.png`. Release ignores the extra (`BuildConfig.DEBUG`). Current screenshots: `docs/screenshots/task06/` (test data only).
- Xiaomi (HyperOS): adb taps need "USB debugging (Security settings)" (then reconnect USB debugging); installs need "Install via USB". HyperOS may block *new* installs over USB ("Block installation" notification) while updates of an installed app still work.

Test vectors in `core-crypto/src/test/resources/`:
- `sample-pvault1.bin`: the desktop sample from `/test-vectors`
- `unicode-pw-pvault1.bin`: a desktop-format file with a non-ASCII password
- `sample-pvault2.bin`: written by the **independent Python reference** `tools/pvault2.py`. Kotlin must open it with both the password and the recovery key.

`tools/pvault2.py` (needs `pip install cryptography argon2-cffi`) creates and opens PVAULT2 files. Use it to cross-check, and as the base for adding PVAULT2 to the desktop app later.

## App structure
- `ui/AppViewModel.kt`: navigation (`Screen`), all actions. Lock events say whether a vault was open; locked-side screens (Onboarding, Create, Unlock, Recovery) keep their input on a lock.
- `ui/Screens.kt`, `ui/Components.kt`, `ui/Theme.kt`: Compose UI. Colours follow the desktop palette and its meaning (orange main action, green add, blue edit, red delete/lock, teal/steel/purple copy, amber show, violet import/new).
- `security/`: `Session` (the one open vault, seal + save), `KdfCalibration`, `Clipboard`, `PasswordStrength`, `MasterPasswordPolicy` (min 6, warning below 10 or "Fair"), `PasswordGenerator`.
- `data/`: `VaultRepository` (atomic save + snapshots, `onSaved` queues the upload), `AppSettings` (non-secret prefs: Emergency Kit flag, Wi-Fi only, fingerprint policy state, reminder clocks), `VaultEdits`.
- `backup/` (Task 07): `PairingPayload` (QR/paste JSON), `Csr` (hand-built PKCS#10), `DeviceKeys` (Keystore EC client key, token AES key, mTLS KeyManager), `HttpBackupTarget` (OkHttp, pins, TLS 1.2+), `BackupManager` (pairing + status in `filesDir/backup/`), `BackupUploader` + `UploadWorker` (WorkManager), `BackupHealth` (chip), `Restore` (fetch/check, rollback rev, test restore), `BackupErrors` (plain-word messages).
- `security/BiometricVault` + `BiometricPolicy` (fingerprint unlock), `security/Reminders` (practice-password / Emergency Kit cards).
- A new vault (and a new recovery key) is saved **before** the Emergency Kit is shown, so a saved or printed kit always matches the file.

## Using core-crypto
```kotlin
// New vault (withRecoveryKey = false: password slot only)
val created = Pvault2.create(password, deviceId, hint = "optional", withRecoveryKey = true)
save(created.bytes)
created.recoveryKey?.let { showEmergencyKit(it.display()) }   // once! then wipe()

// Unlock
val header = Pvault2.peekHeader(raw)                          // unauthenticated, display only
header.hint; header.hasRecoveryKey
val vault = Pvault2.unlockWithPassword(raw, password)        // or unlockWithRecoveryKey / unlockWithDek
vault.payload = vault.payload.copy(entries = vault.payload.entries + Entry(service = "x", password = "y"))
save(vault.seal(deviceId))                                    // rev+1, then upload
vault.wipe()                                                  // on lock

// Recovery key management (then seal + save)
val newKey = vault.regenerateRecoveryKey()                    // replaces, or adds if there is none
vault.removeRecoveryKey()

// Import from desktop
val payload = Pvault1.decrypt(password, desktopFileBytes)
```
