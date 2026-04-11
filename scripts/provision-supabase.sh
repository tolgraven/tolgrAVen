#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <schema|import|all> [firebase-export.json]" >&2
  exit 1
fi

command="$1"
export_path="${2:-}"

case "$command" in
  schema)
    lein run -m tolgraven.provision.supabase.cli schema
    ;;
  import)
    if [[ -z "$export_path" ]]; then
      echo "import requires a firebase export path" >&2
      exit 1
    fi
    lein run -m tolgraven.provision.supabase.cli import "$export_path"
    ;;
  all)
    if [[ -z "$export_path" ]]; then
      echo "all requires a firebase export path" >&2
      exit 1
    fi
    lein run -m tolgraven.provision.supabase.cli schema
    lein run -m tolgraven.provision.supabase.cli import "$export_path"
    ;;
  *)
    echo "Unknown command: $command" >&2
    exit 1
    ;;
esac
