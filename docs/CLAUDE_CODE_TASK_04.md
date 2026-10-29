# Task 04 for Claude Code: build and test core-crypto, commit

1. Check for JDK 17+ (`java -version`). If it's missing, report it. Android Studio (which bundles a JDK) will be needed for M2 anyway. Ask the owner before installing anything.
2. In `android/`: if `gradlew` doesn't exist, run `gradle wrapper --gradle-version 8.14.3` (or use Android Studio's Gradle). Commit the wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`).
3. Run `./gradlew :core-crypto:test` (Windows: `gradlew.bat :core-crypto:test`). All 27 tests must pass. Report the printed "default Argon2id create+unlock" time.
4. Optional cross-check: `pip install cryptography argon2-cffi`, then `python android/tools/pvault2.py open android/core-crypto/src/test/resources/sample-pvault2.bin "Café ☕ correct horse"`.
5. Commit `android/` plus the doc updates (DECISIONS, VAULT_FORMAT_V2, ANDROID_APP_SPEC, this file) with the message `M1: core-crypto (PVAULT1 import, PVAULT2 with recovery key)`, then push. Don't commit `*.tgz`, `build/` or `.gradle/`.
