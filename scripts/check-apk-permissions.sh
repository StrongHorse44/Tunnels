#!/usr/bin/env bash
# Second line of defence for hard rule #1: inspect the built APK itself, not just the manifest.
# Every uses-permission (and uses-permission-sdk-23) in the APK must appear in the union of module
# permissions.allow files that the Gradle verifyPermissions task wrote for this variant.
# aapt2 comes from the pinned build-tools, never the newest one installed on the runner. The pin is BUILD_TOOLS_VERSION,
# which ci.yml and release.yml set for every job (the same pin the release gate's apksigner and aapt2 come from). A
# workflow that does not set it (compile-check.yml) gets the same value read from those two files; the Gradle build
# pins no buildToolsVersion. It fails only when no pin can be found, or the two files disagree.
set -euo pipefail
apk="$1"
variant="${2:-debug}"
union="app/build/reports/permissions/allowed-${variant}.txt"
[ -f "$union" ] || { echo "::error::$union missing; run ./gradlew verifyPermissions first"; exit 1; }
if [ -z "${BUILD_TOOLS_VERSION:-}" ]; then
  pins=$(grep -hoP '^\s*BUILD_TOOLS_VERSION:\s*"?\K[0-9][0-9.]*' .github/workflows/ci.yml .github/workflows/release.yml 2>/dev/null | sort -u || true)
  [ -n "$pins" ] || { echo "::error::BUILD_TOOLS_VERSION is not set and no pin was found in .github/workflows/ci.yml or release.yml"; exit 1; }
  [ "$(wc -l <<<"$pins")" -eq 1 ] || { echo "::error::ci.yml and release.yml pin different build-tools: $(tr '\n' ' ' <<<"$pins")"; exit 1; }
  BUILD_TOOLS_VERSION="$pins"
  echo "build-tools $BUILD_TOOLS_VERSION (the pin in ci.yml and release.yml)"
fi
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
