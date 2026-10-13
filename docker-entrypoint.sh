#!/bin/sh
set -eu
# Only opted-in staging waits. Production retains its existing startup behavior.
if [ "${SUPABASE_WAIT_FOR_READY:-false}" = true ]; then
  : "${SUPABASE_PUBLIC_URL:?Staging requires SUPABASE_PUBLIC_URL}"
  : "${SUPABASE_ANON_KEY:?Staging requires SUPABASE_ANON_KEY}"
  echo 'Waiting for staging Supabase Auth and REST...'
  deadline=$(( $(date +%s) + 300 ))
  until curl --fail --silent --output /dev/null --max-time 5 \
      --header "apikey: $SUPABASE_ANON_KEY" "${SUPABASE_PUBLIC_URL%/}/auth/v1/health" && \
    curl --fail --silent --output /dev/null --max-time 5 \
      --header "apikey: $SUPABASE_ANON_KEY" "${SUPABASE_PUBLIC_URL%/}/rest/v1/"; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo 'Staging Supabase did not become ready; refusing to serve the web app.' >&2
      exit 1
    fi
    sleep 3
  done
  echo 'Staging Supabase ready.'
fi
# The first start records the deployment; subsequent restarts retain it in the
# container writable layer. Operators can supply the platform's exact timestamp.
deployment_file="${DEPLOY_FILE:-/app/deployment.json}"
if [ -d "$(dirname "$deployment_file")" ] && [ ! -f "$deployment_file" ]; then
  revision="${DEPLOY_COMMIT:-}"
  revision_file="${DEPLOY_REVISION_FILE:-/app/revision}"
  if [ -z "$revision" ] && [ -f "$revision_file" ]; then revision=$(cat "$revision_file"); fi
  case "$revision" in unknown|'') revision="${CAPROVER_GIT_COMMIT_SHA:-${SOURCE_COMMIT:-unknown}}";; esac
  case "$revision" in *[!a-fA-F0-9]*|'') revision=unknown;; esac
  repository="${DEPLOY_REPOSITORY:-}"
  if [ -z "$repository" ] && [ -f /app/repository ]; then repository=$(cat /app/repository); fi
  case "$repository" in *[!a-zA-Z0-9_./-]*) repository=unknown;; esac
  # Always record a native UTC timestamp; configured DEPLOYED_AT takes precedence
  # in the server adapter and is parsed before becoming public.
  deployed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '{"commit":"%s","deployed-at":"%s","repository":"%s"}\n' \
    "$revision" "$deployed_at" "$repository" > "$deployment_file.tmp"
  mv "$deployment_file.tmp" "$deployment_file"
fi
exec "$@"
