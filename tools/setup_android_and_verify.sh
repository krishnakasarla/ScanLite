#!/usr/bin/env bash
# Run in a normal Terminal with network access. Installs only inside this project.
# SDK licenses are presented interactively; they are never accepted automatically.
set -euo pipefail

SCANLITE_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$SCANLITE_ROOT"
SCANLITE_LOGS="$SCANLITE_ROOT/verification-logs"
mkdir -p "$SCANLITE_LOGS"
if [[ "${SCANLITE_LOG_CAPTURE:-}" != "1" ]]; then
    SCANLITE_LOG_CAPTURE=1 bash "$0" "$@" 2>&1 | tee "$SCANLITE_LOGS/setup-and-verify.log"
    exit "${PIPESTATUS[0]}"
fi
SCANLITE_STAGE="initializing"
SCANLITE_EMULATOR_PID=""
SCANLITE_SERIAL=""
cleanup() {
    local status=$?
    trap - EXIT
    if [[ -n "$SCANLITE_EMULATOR_PID" ]]; then
        "$ANDROID_HOME/platform-tools/adb" -s "$SCANLITE_SERIAL" emu kill >/dev/null 2>&1 || true
        kill "$SCANLITE_EMULATOR_PID" >/dev/null 2>&1 || true
    fi
    if (( status != 0 )); then
        printf '\nFAILED during: %s (exit %s)\nLogs: %s\n' "$SCANLITE_STAGE" "$status" "$SCANLITE_LOGS"
        printf 'An APK/test success is reported only after its command completes successfully.\n'
    fi
    exit "$status"
}
trap cleanup EXIT
for tool in java python3 curl unzip shasum; do
    command -v "$tool" >/dev/null || { printf 'Missing prerequisite: %s\n' "$tool"; exit 1; }
done
java -version

export ANDROID_HOME="$SCANLITE_ROOT/.android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$SCANLITE_ROOT/.android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export GRADLE_USER_HOME="$SCANLITE_ROOT/.local-gradle"
mkdir -p "$ANDROID_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME" "$SCANLITE_ROOT/.tool-downloads"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

case "$(uname -s):$(uname -m)" in
    Darwin:x86_64)
        sdk_archive="commandlinetools-mac_x86_64-15859902_latest.zip"
        sdk_sha="c5a6378ab5cf7e0d5701921405115befff13e9ff7417fb588389338f8bd050f3"
        image_arch="x86_64" ;;
    Darwin:arm64)
        sdk_archive="commandlinetools-mac_arm64-15859902_latest.zip"
        sdk_sha="835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e"
        image_arch="arm64-v8a" ;;
    Linux:x86_64)
        sdk_archive="commandlinetools-linux-15859902_latest.zip"
        sdk_sha="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
        image_arch="x86_64" ;;
    *) printf 'Unsupported host; install Android Studio and use its SDK Manager.\n'; exit 1 ;;
esac

if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    SCANLITE_STAGE="downloading official Android SDK tools"
    archive_path="$SCANLITE_ROOT/.tool-downloads/$sdk_archive"
    if [[ ! -f "$archive_path" ]]; then
        printf '\nDownloading %s from Google…\n' "$sdk_archive"
        curl --fail --location --retry 2 --connect-timeout 15 --max-time 900 \
            --output "$archive_path.part" "https://dl.google.com/android/repository/$sdk_archive"
        mv "$archive_path.part" "$archive_path"
    fi
    SCANLITE_STAGE="verifying SDK archive SHA-256"
    printf '%s  %s\n' "$sdk_sha" "$archive_path" | shasum -a 256 -c -
    extract_path="$(mktemp -d "$SCANLITE_ROOT/.tool-downloads/extract.XXXXXX")"
    unzip -q "$archive_path" -d "$extract_path"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    if [[ -e "$ANDROID_HOME/cmdline-tools/latest" ]]; then
        printf 'Incomplete SDK directory exists at %s. Rename it and rerun.\n' "$ANDROID_HOME/cmdline-tools/latest"
        exit 1
    fi
    mv "$extract_path/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rmdir "$extract_path"
fi

SCANLITE_STAGE="reviewing SDK licenses"
printf '\nReview Google SDK licenses below. Answer the SDK prompts yourself.\n'
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
SCANLITE_STAGE="installing Android SDK packages"
# API 37 introduced major.minor package names. The package ID is 37.0,
# while Gradle's compileSdk/targetSdk integer values remain 37.
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
[[ -f "$ANDROID_HOME/platforms/android-37.0/android.jar" ]] || { printf 'API 37.0 was not installed. Check SDK package/license output.\n'; exit 1; }
[[ -x "$ANDROID_HOME/build-tools/36.0.0/apksigner" ]] || { printf 'Build Tools were not installed.\n'; exit 1; }

# Preserve other local.properties keys and escape the path as a Java properties value.
python3 - <<'PY'
import os
from pathlib import Path
p = Path('local.properties')
lines = p.read_text().splitlines() if p.exists() else []
lines = [line for line in lines if not line.lstrip().startswith('sdk.dir=')]
value = os.environ['ANDROID_HOME'].replace('\\', '\\\\').replace(':', '\\:').replace(' ', '\\ ')
p.write_text('\n'.join(lines + ['sdk.dir=' + value]) + '\n')
PY

# Allocate a fresh build directory so no earlier APK iteration is overwritten.
mkdir -p "$SCANLITE_ROOT/apk-iterations"
SCANLITE_BUILD_DIR="$(mktemp -d "$SCANLITE_ROOT/apk-iterations/verify-XXXXXXXX")"
SCANLITE_BUILD_REL="apk-iterations/$(basename "$SCANLITE_BUILD_DIR")"
export SCANLITE_BUILD_DIR
SCANLITE_STAGE="downloading Gradle and building APK"
./gradlew --no-daemon --console=plain "-PscanliteBuildDir=$SCANLITE_BUILD_REL" assembleDebug | tee "$SCANLITE_LOGS/assembleDebug.log"
apk="$SCANLITE_BUILD_DIR/outputs/apk/debug/app-debug.apk"
[[ -s "$apk" ]] || { printf 'Gradle returned without the expected APK.\n'; exit 1; }
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose "$apk" | tee "$SCANLITE_LOGS/apk-signature.log"
printf '\nAPK built and signature verified: %s\n' "$apk"

SCANLITE_STAGE="unit tests"
./gradlew --no-daemon --console=plain "-PscanliteBuildDir=$SCANLITE_BUILD_REL" testDebugUnitTest | tee "$SCANLITE_LOGS/unit-tests.log"
SCANLITE_STAGE="Android lint"
./gradlew --no-daemon --console=plain "-PscanliteBuildDir=$SCANLITE_BUILD_REL" lintDebug | tee "$SCANLITE_LOGS/lint.log"

# Use an explicitly selected, authorized device; otherwise launch a dedicated local emulator.
# No unrelated connected phone is selected or modified implicitly.
if [[ -n "${SCANLITE_TEST_SERIAL:-}" ]]; then
    SCANLITE_SERIAL="$SCANLITE_TEST_SERIAL"
    [[ "$(adb -s "$SCANLITE_SERIAL" get-state)" == "device" ]] || { printf 'Selected test device is not authorized/ready.\n'; exit 1; }
else
    SCANLITE_STAGE="installing Android emulator and system image"
    test_api="${SCANLITE_TEST_API:-35}"
    case "$test_api" in 29|30|31|32|33|34|35|36|37) ;; *) printf 'Choose a test API between 29 and 37.\n'; exit 1 ;; esac
    image="system-images;android-$test_api;google_apis;$image_arch"
    sdkmanager --sdk_root="$ANDROID_HOME" "emulator" "$image"
    avd_name="ScanLite_Test_API_${test_api}_${image_arch//-/_}"
    if [[ ! -f "$ANDROID_AVD_HOME/$avd_name.ini" ]]; then
        # This answers the custom hardware-profile question, not a license prompt.
        printf 'no\n' | avdmanager create avd --name "$avd_name" --package "$image"
    fi
    SCANLITE_STAGE="starting test emulator"
    port="${SCANLITE_EMULATOR_PORT:-5580}"
    case "$port" in ''|*[!0-9]*) printf 'Emulator port must be an even integer.\n'; exit 1 ;; esac
    (( port >= 5554 && port <= 5682 && port % 2 == 0 )) || { printf 'Choose an even emulator port from 5554 through 5682.\n'; exit 1; }
    SCANLITE_SERIAL="emulator-$port"
    if adb devices | awk 'NR > 1 { print $1 }' | grep -Fxq "$SCANLITE_SERIAL"; then
        printf 'Emulator port %s is already in use. Choose SCANLITE_EMULATOR_PORT.\n' "$port"
        exit 1
    fi
    emulator -avd "$avd_name" -port "$port" -no-window -no-audio -no-snapshot \
        > "$SCANLITE_LOGS/emulator.log" 2>&1 &
    SCANLITE_EMULATOR_PID=$!
    booted=false
    for (( attempt=0; attempt<180; attempt++ )); do
        kill -0 "$SCANLITE_EMULATOR_PID" 2>/dev/null || { printf 'Emulator stopped; inspect emulator.log.\n'; exit 1; }
        if [[ "$(adb -s "$SCANLITE_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
            booted=true
            break
        fi
        sleep 2
    done
    [[ "$booted" == true ]] || { printf 'Emulator did not boot within six minutes; inspect emulator.log.\n'; exit 1; }
    adb -s "$SCANLITE_SERIAL" shell input keyevent 82 >/dev/null
fi

SCANLITE_STAGE="Android integration tests"
ANDROID_SERIAL="$SCANLITE_SERIAL" ./gradlew --no-daemon --console=plain \
    "-PscanliteBuildDir=$SCANLITE_BUILD_REL" -Pandroid.injected.device.serial="$SCANLITE_SERIAL" connectedDebugAndroidTest \
    | tee "$SCANLITE_LOGS/android-tests.log"

# Gradle must provide real nonempty result files with zero failures/errors/skips.
SCANLITE_STAGE="checking actual test reports"
python3 - <<'PY'
from pathlib import Path
import os
import xml.etree.ElementTree as ET
build = Path(os.environ['SCANLITE_BUILD_DIR'])
for title, folder, expected in [('Unit', build / 'test-results/testDebugUnitTest', 8),
                               ('Android', build / 'outputs/androidTest-results/connected', 13)]:
    files = list(folder.rglob('TEST-*.xml'))
    if not files:
        raise SystemExit(f'No {title} test result XML was produced: {folder}')
    tests = failures = errors = skipped = 0
    for p in files:
        tree = ET.parse(p).getroot()
        suites = [tree] if tree.tag == 'testsuite' else list(tree.iter('testsuite'))
        for suite in suites:
            tests += int(suite.get('tests', 0))
            failures += int(suite.get('failures', 0))
            errors += int(suite.get('errors', 0))
            skipped += int(suite.get('skipped', 0))
    if tests < expected or failures or errors or skipped:
        raise SystemExit(f'{title}: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}')
    print(f'{title}: {tests} tests passed; no failures/errors/skips')
PY
printf '\nAutomated Android verification completed successfully.\nAPK: %s\nReports: %s/reports/ and %s/outputs/androidTest-results/\n' "$apk" "$SCANLITE_BUILD_DIR" "$SCANLITE_BUILD_DIR"
printf 'Physical scanner capture, external sharing apps and the README phone checklist still require manual testing.\n'
