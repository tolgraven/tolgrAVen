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

Deploy this image as a one-shot job or service on the same Docker network as Supabase.

Expected flow:

1. Run `schema` once for a fresh instance.
2. Run `import` or `import-scope` to populate data.
3. Re-run `import-scope` during iteration; it overwrites the target data.

The image does not need public Postgres exposure. It only needs internal network reachability to the database container.
