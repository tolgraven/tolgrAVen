#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
current=$(git config --get core.hooksPath || true)
if [[ -n "$current" && "$current" != .githooks ]]; then
    echo "Existing core.hooksPath ($current): add .githooks/pre-commit to your hook chain." >&2
    exit 1
fi
git config --local core.hooksPath .githooks
echo 'Installed image conversion pre-commit hook.'
