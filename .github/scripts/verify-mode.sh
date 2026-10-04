#!/usr/bin/env bash
# Chooses how Gradle treats dependency verification in this job and prints it to $GITHUB_OUTPUT:
#   flag=...        the Gradle arguments to pass (always on the command line, so a gradle.properties
#                   change can't relax it)
#   written=true    only when gradle/verification-metadata.xml is missing: the job then writes a new one
#                   from its real tasks, reports it (the metadata steps in each Gradle job) and fails at the end
#                   until the file is committed (gate/README.md).
set -euo pipefail
if [ -f gradle/verification-metadata.xml ]; then
  echo "flag=--dependency-verification=strict" >> "$GITHUB_OUTPUT"
else
  echo "::warning::gradle/verification-metadata.xml is missing: this job writes it from its build instead of verifying dependencies, and fails at the end until it is committed."
  # The build cache is off so every resolved configuration is really resolved.
  echo "flag=--write-verification-metadata sha256 --no-build-cache" >> "$GITHUB_OUTPUT"
  echo "written=true" >> "$GITHUB_OUTPUT"
fi
