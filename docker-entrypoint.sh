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
      --header "apikey: $SUPABASE_ANON_KEY" "${SUPABASE_PUBLIC_URL%/}/rest/v1/blog_posts?select=id&limit=1"; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo 'Staging Supabase did not become ready; refusing to serve the web app.' >&2
      exit 1
    fi
    sleep 3
  done
  echo 'Staging Supabase ready.'
fi
exec "$@"
