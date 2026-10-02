# Supabase Provisioning

Use the dedicated provisioning image for first-time schema bootstrap and repeatable data imports.

## Why a separate image

- Fresh instance setup needs direct Postgres access from inside the Supabase network.
- Runtime reads/realtime/writes do not need that same privilege boundary.
- Keeping bootstrap separate makes Coolify provisioning repeatable and avoids coupling schema setup to the web app container.

## Build

```bash
docker build -f Dockerfile.supabase-provision -t tolgraven-supabase-provision .
```

Push that image to a registry Coolify can pull from. This job should be deployed as a Docker Compose / Service Stack resource, not as a plain Docker image application.

## Required environment

Provide one of:

- `SUPABASE_DATABASE_URL`
- `SUPABASE_DB_URL`

Or provide the standard component env vars:

- `POSTGRES_HOSTNAME` or `POSTGRES_HOST`
- `POSTGRES_PORT` optional, defaults to `5432`
- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `SUPABASE_DB_SSLMODE` optional, defaults to `disable`

For REST-based imports and runtime-compatible table resets, also provide:

- `SUPABASE_PUBLIC_URL`
- `SUPABASE_SERVICE_KEY`

If the public certificate is temporarily broken during DNS migration, set:

- `SUPABASE_INSECURE_TLS=true`

## Commands

Schema bootstrap:

```bash
docker run --rm \
  --network <coolify-internal-network> \
  -e POSTGRES_HOSTNAME=supabase-db \
  -e POSTGRES_DB=postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=... \
  tolgraven-supabase-provision \
  schema
```

Full import:

```bash
docker run --rm \
  --network <coolify-internal-network> \
  -e POSTGRES_HOSTNAME=supabase-db \
  -e POSTGRES_DB=postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=... \
  -e SUPABASE_PUBLIC_URL=https://... \
  -e SUPABASE_SERVICE_KEY=... \
  -v "$PWD/firestore-export:/data" \
  tolgraven-supabase-provision \
  import /data/firebase-export.json
```

Scoped import:

```bash
docker run --rm \
  --network <coolify-internal-network> \
  -e POSTGRES_HOSTNAME=supabase-db \
  -e POSTGRES_DB=postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=... \
  -e SUPABASE_PUBLIC_URL=https://... \
  -e SUPABASE_SERVICE_KEY=... \
  -v "$PWD/firestore-export:/data" \
  tolgraven-supabase-provision \
  import-scope blog /data/firebase-export.json
```

## Coolify shape

Deploy this image as a one-shot job on the same Docker network as Supabase.

Do not run it as a long-lived service:

- no ports
- one replica
- no healthcheck
- no automatic restarts
- explicit command such as `doctor`, `schema`, or `import`

### Why not a Docker image app?

In current Coolify, the `Connect to Predefined Network` option is available for service stacks / Docker Compose deployments. It is not exposed the same way for a plain Docker image application. If you need this job to reach `supabase-db-<uuid>`, use a Compose-based resource.

### Recommended resource type

Use `Docker Compose Empty` or a Compose-based deployment and paste [supabase-provision.compose.yaml](/Users/tol/CODE/WEB/tolgrAVen/deploy/coolify/supabase-provision.compose.yaml).

Then:

1. Create the resource from that compose file.
2. Open the stack settings page.
3. Enable `Connect to Predefined Network`.
4. Set the destination to the same one used by the Supabase stack.
5. Set `POSTGRES_HOSTNAME` to the full Coolify-renamed service hostname, for example `supabase-db-e840kco0scs04gkcco44w088`.
6. Set the remaining env vars in Coolify.
7. Change the `command` for each run:
   - `["doctor"]`
   - `["schema"]`
   - `["import", "/data/firebase-export.json"]`
   - `["import-scope", "blog", "/data/firebase-export.json"]`

### Volume for imports

If you want to import a Firebase export from a file, mount it into the Compose stack. One simple pattern is a bind mount:

```yaml
    volumes:
      - /data/firebase-export:/data:ro
```

Then run:

```yaml
command: ["import", "/data/firebase-export.json"]
```

### Expected behavior

- `doctor` should print a configured `:database` target with your full `POSTGRES_HOSTNAME`.
- `schema` should run once and exit `0`.
- `import` should run once and exit `0`.
- Because `restart: "no"` and `exclude_from_hc: true` are set, Coolify should not keep restarting the job after it exits.

Expected flow:

1. Run `doctor` first to confirm the job sees the expected DB target.
2. Run `schema` once for a fresh instance.
3. Run `import` or `import-scope` to populate data.
4. Re-run `import-scope` during iteration; it overwrites the target data.

The image does not need public Postgres exposure. It only needs internal network reachability to the database container.

If you run the container on the host instead of inside the Coolify stack network, `POSTGRES_HOSTNAME=supabase-db` will usually not resolve. In that case the container is not actually in the same network as Supabase, even if it is on the same machine.


## Migration checkpoint (2026-10-03)

Work continues on `codex/strapi-supabase-migration`, which incorporates current
master, including lazy modules, link previews, and the AVIF fixes.

Live instance: https://supabasekong-e840kco0scs04gkcco44w088.bux.tolgraven.se
Use this origin for `SUPABASE_PUBLIC_URL`, without the Studio `/project/...` path.
The browser also needs `SUPABASE_ANON_KEY`; the server needs
`SUPABASE_SERVICE_KEY`. Never send the service key to the browser.

Verified existing data: 11 posts, 75 comments, 7 site profiles, 24 chat messages,
3 generic store documents, and zero Supabase Auth accounts. Do not repeat the
full import just to deploy application code: that command replaces table data.

### Access changes applied to the live instance

All seven tables now have RLS enabled. Anonymous and authenticated roles have
column-level SELECT access to the public post, comment, chat, and profile fields.
Email, raw import JSON, vote history, service configuration, roles, and generic
documents are not public. Anonymous SELECT was verified against the live database:
11 posts, 75 comments, and 7 profiles remain readable; email, service configuration,
and generic documents are denied. The same grants are included in `schema.sql`.
No content rows were changed by this access update. Security Advisor reports zero
errors and zero warnings; its three informational notices are the deliberately
policy-free private tables (`auth_roles`, `service_configs`, `store_documents`).
This follows the grants/RLS separation in the [Supabase RLS guide](https://supabase.com/docs/guides/database/postgres/row-level-security).

### Application bridge

- Public fallback queries accept only the known public collections, fetch only
  those tables/columns, and page through results. Private and unknown collections
  return 403 before database access.
- Browser reads normalize keyword paths and use explicit public columns with
  stable pagination. The seed/result argument order and REST header merging are fixed.
- The compatibility write endpoint is temporarily protected by the existing
  server administrator Basic authentication (`AUTH_USER` / `AUTH_PASS`). It is
  **not** the final end-user write API. Missing administrator credentials deny access.
- Administrator writes upsert only changed rows; they never reset tables.
  Row removal, derived post-ID writes, and nested post-comment writes are rejected.
  Multi-row upserts are not a transaction, and concurrent edits to one row still
  need a dedicated transactional API before enabling end-user writes.
- Bootstrap submits the complete SQL script in a transaction so PostgreSQL can
  parse DO blocks correctly.

### Remaining cutover work

Supabase Auth identities must be linked to the existing Firebase profile IDs
before replacing login. Firebase authentication is still initialized in the app.
Replace legacy comment/vote/chat write sequences with authenticated transactional
operations. Move service integration credentials behind server endpoints before
removing their existing browser-side store reads. Strapi content migration remains
separate. This branch is not a completed production cutover.

Validation: `lein test tolgraven.supabase-shape-test` and
`lein run -m shadow.cljs.devtools.cli compile app`.
