#!/usr/bin/env bash
# Second line of defence for hard rule #1: inspect the built APK itself, not just the manifest.
# Every uses-permission (and uses-permission-sdk-23) in the APK must appear in the union of module
# permissions.allow files that the Gradle verifyPermissions task wrote for this variant.
# aapt2 comes from the pinned build-tools, BUILD_TOOLS_VERSION, which ci.yml and release.yml set for every job
# (the same pin the release gate's apksigner and aapt2 come from), never the newest one installed on the runner.
set -euo pipefail
apk="$1"
variant="${2:-debug}"
union="app/build/reports/permissions/allowed-${variant}.txt"
[ -f "$union" ] || { echo "::error::$union missing; run ./gradlew verifyPermissions first"; exit 1; }
[ -n "${BUILD_TOOLS_VERSION:-}" ] || { echo "::error::BUILD_TOOLS_VERSION is not set; the workflow pins it"; exit 1; }
aapt2="${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools/$BUILD_TOOLS_VERSION/aapt2"
[ -x "$aapt2" ] || { echo "::error::$aapt2 not found: build-tools $BUILD_TOOLS_VERSION is not installed"; exit 1; }
dump=$("$aapt2" dump permissions "$apk")
echo "$dump"
pkg=$(grep -oP "^package: \K\S+" <<<"$dump")
perms=$(grep -oP "^\s*uses-permission(-sdk-23)?: name='\K[^']+" <<<"$dump" || true)
# Tunnels always declares permissions, so none parsed means the dump format changed: fail, never print OK.
[ -n "$perms" ] || { echo "::error::no uses-permission lines parsed from $apk; has the aapt2 dump format changed?"; exit 1; }
status=0
count=0
while read -r perm; do
  count=$((count + 1))
  [[ "$perm" == "$pkg".* ]] && continue   # the app's own self-defined permissions
  grep -qxF "$perm" "$union" || { echo "::error::$apk declares $perm, which no module allows"; status=1; }
done <<<"$perms"
[ $status -eq 0 ] && echo "OK: all $count permissions in $apk are allowed by a module"
exit $status
