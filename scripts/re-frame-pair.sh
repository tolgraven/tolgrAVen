#!/usr/bin/env bash
# Run the user-installed skill against this project's browser development build.
set -euo pipefail
cd "$(dirname "$0")/.."
skill_dir="${REFRAME_PAIR_SKILL_DIR:-${CODEX_HOME:-$HOME/.codex}/skills/re-frame-pair}"
operation="${1:-discover-app}"
if [[ $# -gt 0 ]]; then shift; fi
case "$operation" in
  discover-app|app-summary|eval-cljs|console-tail|handler-source|inject-runtime|dispatch|trace-recent|watch-epochs|tail-build) ;;
  *) echo "Unknown re-frame-pair operation: $operation" >&2; exit 2 ;;
esac
if [[ ! -f "$skill_dir/scripts/$operation.sh" ]]; then
  echo "Install day8/re-frame-pair in $skill_dir (see doc/re-frame-pair.md)." >&2
  exit 1
fi
export SHADOW_CLJS_BUILD_ID="${SHADOW_CLJS_BUILD_ID:-app-dev}"
# Lein's dev nREPL has Shadow middleware. Prefer its port over a stale standalone
# Shadow CLI port left by a test/SSR compilation; allow explicit overrides.
if [[ -z "${SHADOW_CLJS_NREPL_PORT:-}" && -f .nrepl-port ]]; then
  export SHADOW_CLJS_NREPL_PORT="$(tr -d '[:space:]' < .nrepl-port)"
fi
exec bash "$skill_dir/scripts/$operation.sh" "$@"
