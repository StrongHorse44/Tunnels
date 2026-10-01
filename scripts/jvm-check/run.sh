#!/usr/bin/env bash
# Runs Gradle tasks for the plain-Kotlin modules only, e.g. scripts/jvm-check/run.sh :core:elf:test
# Retries on Maven Central rate limiting (HTTP 429).
set -u
cd "$(dirname "$0")"
GRADLE=${GRADLE:-$(command -v gradle || echo /opt/gradle-8.14.3/bin/gradle)}
for i in 1 2 3 4 5; do
  "$GRADLE" --console=plain -q --max-workers=1 "$@" > run.log 2>&1 && { grep -v JAVA_TOOL run.log; echo "OK"; exit 0; }
  if grep -q "429" run.log; then echo "429 from Maven Central, retry $i"; sleep $((20 * i)); else break; fi
done
grep -v JAVA_TOOL run.log | tail -60; exit 1
