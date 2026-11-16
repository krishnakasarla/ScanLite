# ScanLite

ScanLite is an Android document scanner built with Kotlin and Jetpack Compose. Scan a document or import photos, review the pages, and save them as JPEG, JPG, PNG, or PDF. It supports Android 10 (API 29) and later.

## Features

- Scan with Google ML Kit's document scanner or import images with the Android photo picker.
- Rotate pages, choose an export format, set an optional size limit, and review the processed result before saving.
- Save to Downloads/ScanLite or a folder selected in Settings. Open, share, and delete saved files from History.
- Choose light, dark, or system appearance in Settings.

ML Kit provides its own scanning and editing screen, and requires compatible Google Play services. Its scanner components may need to download on first use. The app itself does not request Internet or broad camera/storage permissions.

## Getting started

Open this directory in Android Studio with AGP 9.1.1 support. Use JDK 17 or 21, Android SDK Platform 37.0 (`platforms;android-37.0`), and Build Tools 36.0.0. Set `ANDROID_HOME` or let Android Studio configure `local.properties`. Initial dependency downloads require network access. AGP 9 provides built-in Kotlin support; do not add the legacy Kotlin Android plugin.

Create a debug APK without overwriting an existing iteration:

```sh
./tools/build_debug.sh
```

The script prints the new APK path under `apk-iterations/`. Install that file on an authorized device with `adb install -r <apk-path>`. Debug APKs are for development; a user-facing release requires separate release signing and verification.

## Creating a signed release APK

Keep the release keystore and its password outside version control and back up both securely. Future updates must use the same signing key. Set the Android SDK path, keystore path, and key alias, then run the release script in an interactive terminal:

```sh
export ANDROID_HOME="/path/to/android-sdk"
export SCANLITE_KEYSTORE="/secure/path/to/release-keystore.p12"
export SCANLITE_KEY_ALIAS="your-key-alias"
./tools/build_release.sh
```

The script prompts for the keystore and key passwords without displaying them, builds into a new `apk-iterations/` directory, aligns and signs the APK, and verifies its signature. For automation, `SCANLITE_STORE_PASSWORD` and `SCANLITE_KEY_PASSWORD` can be supplied securely as environment variables. Never commit passwords or the keystore. Increment `versionCode` and update `versionName` before publishing a subsequent release.

A release-signed build cannot update an installed debug-signed build with a different signing key. Uninstalling the debug app removes its local history; exported documents in shared storage remain.

## Using the app

1. Tap **Scan Document** or **Import from Gallery**. Complete scanning and any cropping or cleanup in the scanner interface.
2. In Preview & Export, navigate or rotate pages, choose a format, and optionally enter a maximum size. JPEG and JPG use the same encoding with different extensions.
3. Tap **Prepare preview**, review the processed result, then tap **Save files**. Changes to export settings or rotation require preparing the preview again.
4. Use the gear button on Home to choose the theme and default save location. Open or share saved files from History.

For image exports the size limit applies per file; for PDF it applies to the entire document. Limits are measured in binary units (1 KB = 1,024 bytes; 1 MB = 1,048,576 bytes). Some targets cannot be reached without excessive reduction; the app reports failure rather than saving an oversized output. Review the result for readability.

## Tests and verification

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
# With a connected, authorized device or emulator:
./gradlew connectedDebugAndroidTest
```

`python3 tools/run_core_checks.py` runs additional checks against platform-independent production Kotlin. It requires `kotlinc` or the project's cached Gradle wrapper distribution. These checks do not replace Android tests.

`tools/setup_android_and_verify.sh` can install SDK components, build to a fresh APK iteration, run unit tests and lint, and start an emulator for instrumented tests. It prompts for SDK licenses and needs network access. Set `SCANLITE_TEST_SERIAL` to use a specific connected device instead. Logs go to the ignored `verification-logs/` directory.

The latest local debug build and all eight unit tests passed. Android lint completed with no errors and 14 warnings. Android integration and manual device testing have not been established as passing for the current version.

Before release, test camera capture and scanner editing, multi-page import, each export format and size limit, both save locations, open/share/delete, permission revocation, app recreation, and light/dark themes on a device.

## Privacy and limitations

ScanLite has no account, analytics, or cloud storage of its own. A folder selected through Android's document picker may be managed or synced by another provider; files shared with another app are handled by that app. An unchanged JPEG may be copied with its original EXIF metadata, including location if present.

Imported pages and processed previews use temporary private cache files. Android may remove cached files under storage pressure. Saved documents live in the selected destination and are not removed when the cache is cleared. PDF exports contain images and do not provide OCR or selectable text. Scanner controls and component availability depend on Google Play services.

## Project layout

- `app/src/main/` — app source, manifest, and resources; `app/schemas/` contains Room schemas.
- `app/src/test/` and `app/src/androidTest/` — local unit and Android device tests.
- `tools/` — build and supplementary verification scripts.
- `apk-iterations/` — local, ignored build outputs; existing APKs are preserved.

See [AGENTS.md](AGENTS.md) for contributor guidelines and [LICENSE](LICENSE) for licensing.
