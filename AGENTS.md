# Repository Guidelines

## Project Structure & Module Organization

ScanLite is a single-module Kotlin Android app using Jetpack Compose. Production code lives in `app/src/main/java/com/scanlite/`: `ui/` contains screens and state, `scanner/` configures ML Kit, `processing/` handles image and PDF exports, `storage/` manages saved files, and `data/local/` contains Room history. Resources and the manifest are under `app/src/main/`; exported Room schemas are in `app/schemas/`. Unit tests live in `app/src/test/`, device tests in `app/src/androidTest/`, and supplementary verification scripts in `tools/`.

## Build, Test, and Development Commands

Use JDK 17 or 21, Android SDK Platform 37 and Build Tools 36.0.0. Configure the SDK through `local.properties` or `ANDROID_HOME`.

- `./tools/build_debug.sh` builds a debug APK in a fresh `apk-iterations/` directory.
- `./gradlew testDebugUnitTest` runs local JUnit tests.
- `./gradlew lintDebug` runs Android lint.
- `./gradlew connectedDebugAndroidTest` runs instrumented tests on a connected device or emulator.
- `python3 tools/run_core_checks.py` runs supplementary offline checks against production Kotlin logic.

Install the newly built debug APK with `adb install -r <apk-path>` using the path printed by the script. See CONTRIBUTING.md for setup and manual device checks.

## Coding Style & Naming Conventions

Follow the existing Kotlin style: four-space indentation, `PascalCase` for types and composables, and `camelCase` for functions and properties. Keep packages aligned with their responsibilities and use descriptive names. Run Android lint before submitting changes. AGP supplies Kotlin support; do not add the legacy Kotlin Android plugin.

## Testing Guidelines

Use JUnit 4 for local tests and AndroidX Test for device tests. Name test classes after the behavior or component they cover, ending in `Test`. Add focused tests for changed behavior, especially export sizes, file integrity, and persistence. Run the relevant unit and instrumented suites; document any device checks that could not be performed. No coverage threshold is configured.

## Commits & Pull Requests

Git history is not available in this workspace, so no established commit convention can be verified. Use concise, imperative commit subjects describing the change. Pull requests should explain the behavior and motivation, link relevant issues, list verification performed, and include screenshots for visible UI changes.

## Configuration & Privacy

Do not commit local SDK paths, build outputs, caches, or scanned documents. Preserve the app's local-first design and review any new permissions or data handling carefully. Commit Room schema updates when database changes require them.

## APK Iterations

Preserve every existing APK when building a new iteration. Direct each new build to a unique build directory and deliver it under a distinct path; do not overwrite `app/build/outputs/apk/debug/app-debug.apk` or earlier iteration artifacts.
