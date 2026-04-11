#!/usr/bin/env bash
set -euo pipefail

cd /app

if [[ $# -eq 0 ]]; then
  echo "Usage: entrypoint-supabase-provision.sh <schema|reset|import|import-scope|dump-seed> [args...]" >&2
  exit 1
fi

exec lein run -m tolgraven.provision.supabase.cli "$@"
