#!/usr/bin/env bash
set -euo pipefail

cd /app

if [[ $# -eq 0 ]]; then
  echo "Usage: entrypoint-supabase-provision.sh <doctor|schema|reset|import|import-scope|dump-seed> [args...]" >&2
  exit 1
fi

exec lein with-profile provision run -m website.provision.supabase.cli "$@"
