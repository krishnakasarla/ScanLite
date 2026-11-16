#!/usr/bin/env bash
# Create a new build directory for every debug APK; never overwrite prior iterations.
set -euo pipefail
root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$root"
mkdir -p apk-iterations
build_dir="$(mktemp -d "$root/apk-iterations/debug-XXXXXXXX")"
build_rel="apk-iterations/$(basename "$build_dir")"
./gradlew "-PscanliteBuildDir=$build_rel" :app:assembleDebug "$@"
printf '\nDebug APK: %s/outputs/apk/debug/app-debug.apk\n' "$build_dir"
