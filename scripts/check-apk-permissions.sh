#!/usr/bin/env bash
# Second line of defence for hard rule #1: inspect the built APK itself, not just the manifest.
set -euo pipefail
apk="$1"
build_tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
perms=$("$build_tools/aapt2" dump permissions "$apk")
echo "$perms"
if grep -q "android.permission.INTERNET" <<<"$perms"; then
  echo "::error::$apk declares android.permission.INTERNET"
  exit 1
fi
echo "OK: $apk has no INTERNET permission"
