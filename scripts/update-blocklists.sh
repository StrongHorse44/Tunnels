#!/usr/bin/env bash
# Refreshes the blocklists bundled in the Traffic tunnel's assets. Run before a release and commit the result;
# the app itself never downloads a list. Each output keeps the list's licence and source in its header.
set -euo pipefail
cd "$(dirname "$0")/.."
out=tunnels/traffic/src/main/assets/blocklists
mkdir -p "$out"

fetch_hosts() { # id url licence
  local id=$1 url=$2 licence=$3 tmp
  tmp=$(mktemp)
  curl -fsSL "$url" -o "$tmp"
  {
    echo "# $id blocklist, bundled with Tunnels"
    echo "# Source: $url"
    echo "# Licence: $licence"
    echo "# Fetched: $(date -u +%Y-%m-%d)"
    # Hostnames only: drop comments, addresses and localhost entries; lowercase, sorted, unique.
    sed -e 's/#.*//' "$tmp" | awk 'NF >= 2 { for (i = 2; i <= NF; i++) print tolower($i) }' |
      grep -Ev '^(localhost|localhost\.localdomain|local|broadcasthost|ip6-localhost|ip6-loopback|0\.0\.0\.0)$' |
      grep -E '^[a-z0-9_-]+(\.[a-z0-9_-]+)+$' | sort -u
  } > "$out/$id.txt"
  rm -f "$tmp"
  echo "$id: $(grep -vc '^#' "$out/$id.txt") hosts"
}

fetch_hosts adaway https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt \
  "CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/), AdAway contributors, https://github.com/AdAway/adaway.github.io"
