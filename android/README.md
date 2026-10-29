# Mobile Vault: Android project

| Module | Status | |
|---|---|---|
| `core-crypto` | ✅ M1 | Pure Kotlin/JVM. PVAULT1 import/export (desktop), PVAULT2 vault format with password + recovery-key slots, Argon2id, recovery key, password-hint policy, entry model |
| `app` | ⏳ M2 | Jetpack Compose UI (next milestone) |

## Build & test
Requirements: JDK 17+ (Android Studio's bundled JDK works).
```
gradle wrapper --gradle-version 8.14.3   # once, if gradlew is missing
./gradlew :core-crypto:test
```
Test vectors in `core-crypto/src/test/resources/`:
- `sample-pvault1.bin`: the desktop sample from `/test-vectors`
- `unicode-pw-pvault1.bin`: a desktop-format file with a non-ASCII password
- `sample-pvault2.bin`: written by the **independent Python reference** `tools/pvault2.py`. Kotlin must open it with both the password and the recovery key.

`tools/pvault2.py` (needs `pip install cryptography argon2-cffi`) creates and opens PVAULT2 files. Use it to cross-check, and as the base for adding PVAULT2 to the desktop app later.

## Using core-crypto
```kotlin
// New vault
val created = Pvault2.create(password, deviceId, hint = "optional")
showEmergencyKit(created.recoveryKey.display())   // once! then created.recoveryKey.wipe()
save(created.bytes)

// Unlock
val hint = Pvault2.peekHeader(raw).hint            // unauthenticated, display only
val vault = Pvault2.unlockWithPassword(raw, password)   // or unlockWithRecoveryKey / unlockWithDek
vault.payload = vault.payload.copy(entries = vault.payload.entries + Entry(service = "x", password = "y"))
save(vault.seal(deviceId))                         // rev+1, then upload
vault.wipe()                                       // on lock

// Import from desktop
val payload = Pvault1.decrypt(password, desktopFileBytes)
```
