#!/usr/bin/env bash
# Second line of defence for hard rule #1: inspect the built APK itself, not just the manifest.
# Every uses-permission in the APK must appear in the union of module permissions.allow files
# that the Gradle verifyPermissions task wrote for this variant.
set -euo pipefail
apk="$1"
variant="${2:-debug}"
union="app/build/reports/permissions/allowed-${variant}.txt"
[ -f "$union" ] || { echo "::error::$union missing; run ./gradlew verifyPermissions first"; exit 1; }
build_tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
dump=$("$build_tools/aapt2" dump permissions "$apk")
echo "$dump"
pkg=$(grep -oP "^package: \K\S+" <<<"$dump")
status=0
while read -r perm; do
  [[ "$perm" == "$pkg".* ]] && continue   # the app's own self-defined permissions
  grep -qxF "$perm" "$union" || { echo "::error::$apk declares $perm, which no module allows"; status=1; }
done < <(grep -oP "uses-permission: name='\K[^']+" <<<"$dump")
[ $status -eq 0 ] && echo "OK: every permission in $apk is allowed by a module"
exit $status
