# Contributing to ScanLite

Thanks for helping improve ScanLite. Please describe the problem and the behavior your change introduces, link any relevant issue, and include screenshots for visible UI changes.

## Set up

Open the project in Android Studio with AGP 9.1.1 support. Use JDK 17 or 21, Android SDK Platform 37.0 (`platforms;android-37.0`), and Build Tools 36.0.0. Set `ANDROID_HOME` or let Android Studio configure `local.properties`. Initial dependency downloads require network access. AGP 9 provides built-in Kotlin support; do not add the legacy Kotlin Android plugin.

Build a debug APK without overwriting an existing iteration:

```sh
./tools/build_debug.sh
```

The script prints the new APK path under `apk-iterations/`. Install it on an authorized device with `adb install -r <apk-path>`. Existing APK iterations and local build outputs must be preserved.

## Tests and verification

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
# With a connected, authorized device or emulator:
./gradlew connectedDebugAndroidTest
```

`python3 tools/run_core_checks.py` runs supplementary checks against platform-independent production Kotlin. It requires `kotlinc` or the project's cached Gradle wrapper distribution and does not replace Android tests.

`tools/setup_android_and_verify.sh` can install SDK components, build to a fresh APK iteration, run unit tests and lint, and start an emulator for instrumented tests. It prompts for SDK licenses and needs network access. Set `SCANLITE_TEST_SERIAL` to use a specific connected device instead. Logs go to the ignored `verification-logs/` directory.

For release candidates, check camera capture and scanner editing, multi-page import, export formats and size limits, both save locations, open/share/delete, permission revocation, app recreation, and light/dark themes on a device. Android integration tests have not yet been established as passing for the current release.

## Release signing

Only a maintainer with access to the existing release-signing key can sign updates to the published app. Keep the keystore and its password outside version control and back up both securely. Set the Android SDK path, keystore path, and key alias, then run the release script in an interactive terminal:

```sh
export ANDROID_HOME="/path/to/android-sdk"
export SCANLITE_KEYSTORE="/secure/path/to/release-keystore.p12"
export SCANLITE_KEY_ALIAS="your-key-alias"
./tools/build_release.sh
```

The script prompts for the keystore and key passwords without displaying them, builds into a new `apk-iterations/` directory, aligns and signs the APK, and verifies its signature. For automation, `SCANLITE_STORE_PASSWORD` and `SCANLITE_KEY_PASSWORD` can be supplied securely as environment variables. Never commit passwords or the keystore. Increment `versionCode` and update `versionName` before publishing a subsequent release.

A release-signed build cannot update an installed debug-signed build with a different signing key. Uninstalling the debug app removes its local history; exported documents in shared storage remain.

## Project layout

- `app/src/main/` — app source, manifest, and resources; `app/schemas/` contains Room schemas.
- `app/src/test/` and `app/src/androidTest/` — local unit and Android device tests.
- `tools/` — build and supplementary verification scripts.
- `apk-iterations/` — local, ignored build outputs; existing APKs are preserved.

See [AGENTS.md](AGENTS.md) for repository-specific conventions and [LICENSE](LICENSE) for licensing.
