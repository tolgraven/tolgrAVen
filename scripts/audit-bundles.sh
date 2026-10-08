#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
label="${1:-current}"
case "$label" in
  ''|*[!a-zA-Z0-9_-]*) echo 'Use an alphanumeric audit label (hyphens/underscores allowed).' >&2; exit 2 ;;
esac
lein with-profile prod,provided run -m tolgraven.build.audit "$label"
node scripts/bundle-sizes.mjs "target/bundle-audit/$label"
