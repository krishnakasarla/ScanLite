#!/usr/bin/env bash
# Build an unsigned release in a fresh directory, then sign and verify a new APK.
set -euo pipefail
root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$root"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
: "${SCANLITE_KEYSTORE:?Set SCANLITE_KEYSTORE to your release keystore path}"
: "${SCANLITE_KEY_ALIAS:?Set SCANLITE_KEY_ALIAS to the key alias}"
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
zipalign="$ANDROID_HOME/build-tools/36.0.0/zipalign"
[[ -f "$SCANLITE_KEYSTORE" ]] || { printf 'Release keystore not found.\n' >&2; exit 1; }
[[ -x "$apksigner" && -x "$zipalign" ]] || { printf 'Android Build Tools 36.0.0 not found.\n' >&2; exit 1; }
# Use environment variables for noninteractive builds; otherwise prompt without echo.
if [[ -z "${SCANLITE_STORE_PASSWORD:-}" ]]; then
    [[ -t 0 ]] || { printf 'Set SCANLITE_STORE_PASSWORD for noninteractive builds.\n' >&2; exit 1; }
    read -r -s -p 'Keystore password: ' SCANLITE_STORE_PASSWORD; printf '\n'
fi
if [[ -z "${SCANLITE_KEY_PASSWORD:-}" ]]; then
    [[ -t 0 ]] || { printf 'Set SCANLITE_KEY_PASSWORD for noninteractive builds.\n' >&2; exit 1; }
    read -r -s -p 'Key password: ' SCANLITE_KEY_PASSWORD; printf '\n'
fi
export SCANLITE_STORE_PASSWORD SCANLITE_KEY_PASSWORD
trap 'unset SCANLITE_STORE_PASSWORD SCANLITE_KEY_PASSWORD' EXIT
mkdir -p apk-iterations
build_dir="$(mktemp -d "$root/apk-iterations/release-XXXXXXXX")"
build_rel="apk-iterations/$(basename "$build_dir")"
./gradlew "-PscanliteBuildDir=$build_rel" :app:assembleRelease "$@"
unsigned="$build_dir/outputs/apk/release/app-release-unsigned.apk"
aligned="$build_dir/outputs/apk/release/app-release-aligned.apk"
signed="$build_dir/outputs/apk/release/ScanLite-release.apk"
[[ -s "$unsigned" ]] || { printf 'Unsigned APK not found.\n' >&2; exit 1; }
"$zipalign" -f -p 4 "$unsigned" "$aligned"
"$apksigner" sign --ks "$SCANLITE_KEYSTORE" --ks-key-alias "$SCANLITE_KEY_ALIAS" \
    --ks-pass env:SCANLITE_STORE_PASSWORD --key-pass env:SCANLITE_KEY_PASSWORD --out "$signed" "$aligned"
"$zipalign" -c -p 4 "$signed"
"$apksigner" verify --verbose --print-certs "$signed"
printf '\nSigned release APK: %s\n' "$signed"
