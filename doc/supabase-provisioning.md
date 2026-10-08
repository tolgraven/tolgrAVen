# Supabase Provisioning

For complete site projects with isolated production/staging stacks, use
[site-provisioning.md](site-provisioning.md). The one-shot image below remains
useful for explicit schema/import jobs.

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


## Runtime data and authentication

Supabase supplies runtime data and authentication. Components read re-frame
subscriptions; source adapters install results in app-db. Public query readers
batch compatible filters, share invalidation channels and retain their caches
when unmounted. Private caches and late responses are guarded by account identity.
RLS trusts `app_metadata.site_user_id` (or native Auth UUID), never user metadata.

Every runtime write
verifies the bearer session with Auth and derives its actor on the server:

| Endpoint | Behavior |
| --- | --- |
| `GET/PUT /api/supabase/profile` | Own profile and private vote history/roles; field-scoped edits. |
| `POST /api/supabase/posts` | Blogger/admin publishing, generated IDs/timestamps/permalinks, owner/admin edits. |
| `POST /api/supabase/chat` | Server-generated messages by the signed-in author. |
| `POST/PUT /api/supabase/comments` | Transactional comment/reply creation and owned edits. |
| `POST /api/supabase/votes` | Atomic vote deltas and karma, preserving imported baselines. |
| `POST /api/supabase/documents` | Owner-only GPT documents/threads; protected configuration is rejected. |
| `POST /api/supabase/avatar` | Validate/re-encode an image, convert WebP/AVIF, and publish all three owned objects to Supabase Storage. |
| `GET /api/integrations/search` | Native Supabase full-text/prefix search of public posts and comments. |

The `user_documents` table is private, RLS-enabled, and published to Realtime.
Only explicitly owned legacy threads are copied from the archive. The new public
`avatars` bucket permits public image reads; browser writes remain denied, and
only the verified server upload endpoint chooses filenames. The endpoint accepts
images up to 5 MB and 4096 pixels per dimension.

Reapply `operations.sql` when upgrading an existing installation: the avatar
bucket must allow `image/png`, `image/webp`, and `image/avif`. Browser writes remain
denied. Converted objects use `avatars/<verified-profile-id>/<sha256>.{png,webp,avif}`;
the profile points to the PNG and the shared picture component selects modern
variants, with direct PNG fallback on decoding failure. The profile is updated
only after all three uploads succeed. Older avatars remain readable.

Conversion uses the same `scripts/convert-images.sh` as bundled assets, with two
concurrent jobs, a 45-second job timeout and ImageMagick resource limits. Install
`webp` and ImageMagick with AVIF support for local server uploads. The runtime
Docker stage installs and smoke-tests these codecs. `:image-converter` optionally
sets the server script path; production uses `/app/scripts/convert-images.sh`.
Temporary encoding files are removed after each request. Supabase's Storage/MinIO
backend must retain its persistent mount (MinIO `/data` in Coolify); no uploaded
media belongs in the web image or `resources/public`. Back up both Storage's
Postgres metadata and mounted object files. Versioned objects remain available
for cached pages; deleting superseded objects requires a separate retention policy.

Integration tokens are read from private `service_configs` on the server. Strava
refreshes tokens there; Intervals and Instagram are proxied. Imported Instagram
posts remain a fallback when the upstream token/endpoint fails. Imagor signing and
the Strapi read token stay on the server. Search uses native indexed Supabase rows
and no longer needs Typesense configuration.

Use `SUPABASE_PUBLIC_URL` and `SUPABASE_ANON_KEY` for public browser settings.
Keep `SUPABASE_SERVICE_KEY` / `SUPABASE_SERVICE_ROLE_KEY` server-only. Local
configuration accepts `:supabase-public-url`, `:supabase-anon-key` and
`:service-supabaseservice-key`; environment variables take precedence.
See [testing](testing.md) for disposable database and live integration checks.

## Supabase login and profile linkage

The Supabase provider now uses Supabase Auth for email/password login, sign-up,
OAuth, restored sessions, token refresh, and sign-out. Configure enabled OAuth
providers, the app site URL, and allowed redirect URLs in the self-hosted Auth
configuration before trying those flows. The callback returns to the app origin.
Email confirmation requires working SMTP. No accounts are created or messages
sent by deploying this code.

The server validates each profile request through `/auth/v1/user`. It does not
trust a decoded client JWT or `user_metadata`. Profile updates accept only name,
avatar, and background colour; the server chooses the profile ID. The browser
keeps tokens in the Supabase session, outside re-frame app-db.

New accounts use their Supabase UUID as the profile ID. For an existing Firebase
profile, first create/confirm the matching Supabase account, then run from the
trusted provisioning environment:

```sh
lein run -m tolgraven.provision.supabase.cli link-user <supabase-account-uuid> <firebase-profile-id>
```

This requires `SUPABASE_PUBLIC_URL` and `SUPABASE_SERVICE_KEY`, checks that the
account's confirmed email matches the imported profile, and sets the
administrator-controlled `app_metadata.site_user_id` claim. It preserves other
metadata and refuses to replace a different existing link. Existing post/comment
ownership therefore keeps the old profile ID. Passwords are not imported by this
command. Refresh the page after linking to reload the profile.

Provider credentials and SMTP configuration determine which login and email
confirmation flows are available. See the account-import guide below for
preserving legacy ownership and passwords.

## Authenticated chat, comments and votes

The browser sends its Supabase access token to the application server:

| Endpoint | Body | Behavior |
| --- | --- | --- |
| `POST /api/supabase/chat` | `text` | Generate a message ID and timestamp; save as the verified profile. |
| `POST /api/supabase/comments` | `post-id`, `text`, optional `parent-id`, `title` | Validate the post/reply relationship; create the comment and update the author's list/count together. |
| `PUT /api/supabase/comments` | `comment-id`, `text`, optional `title` | Edit only an owned comment's text/title. |
| `POST /api/supabase/votes` | `comment-id`, `vote` (`up`, `down`, `none`) | Set the user's desired vote and adjust score/author karma by the delta. |

Each endpoint verifies the token through Auth and derives the actor from the
server-managed profile link. Client author IDs, timestamps, scores, counters,
paths and karma are rejected. Each mutation is a single Postgres RPC transaction.
RPCs use invoker permissions and an empty search path. Only `service_role` can
execute them; browsers cannot submit a forged actor directly to the Data API.

`comment_votes` is a private RLS-enabled ledger. Existing vote history such as
`[24 105 108] -> up` supplies the initial baseline without resetting imported
scores or karma. Zero-vote ledger entries override that baseline after removal.
Repeated requests to set the same vote do not change the score. Profile reads
merge imported history with the ledger and return `comment-votes` only to its
verified owner. Profile locks use a stable order; comment score locks allow
concurrent replies while serializing vote changes.

The UI keeps drafts on failed requests, blocks duplicate in-flight submissions,
applies public changes from Realtime, fetches private vote/profile details after
successful writes, and clears pending state when
accounts change. Chat accepts both imported numeric IDs and new UUID IDs. Comment
editing compares profile IDs and now opens the existing text in the form.

### Install the additive operations on an existing instance

`schema` bootstrap now includes `resources/supabase/operations.sql`. For an
already provisioned database, apply that file inside a transaction from the
trusted database environment, without an import/reset:

```sh
psql -v ON_ERROR_STOP=1 --single-transaction -f resources/supabase/operations.sql
```

Then deploy the application branch with the URL, anon key and server service key
configured as above. Do not run full or scoped Firebase imports after native
writes: those commands replace table data and cascade-delete the native vote
ledger. A future import needs a deliberate merge strategy for native data.

## Firebase Auth account import

The importer preserves Firebase password hashes in Supabase's native `$fbscrypt$`
format and derives deterministic Auth UUIDs from project/UID. Trusted
`app_metadata.site_user_id` preserves existing content ownership. Verify that the
target Auth version supports the hash format before importing.

### Repeatable provisioning tool

Keep the Auth export, hash configuration and generated SQL in a private, ignored
directory. The Auth export must contain `projectId`, optional `exportedAt` (ISO
8601), and `users` in Firebase's JSON export shape (`localId`, `providerUserInfo`,
`passwordHash`, `salt`, etc.). The ordinary Firebase CLI export contains `users`;
add its source `projectId` before running this tool. If the configuration uses a
numeric project resource, also provide `projectNumber` obtained from
`gcloud projects describe <project-id> --format=value(projectNumber)`. This is a separate export
from Firestore. A Firebase credential with `firebaseauth.configs.getHashConfig`
is required to obtain actual hashes; redacted hashes are rejected.

Hash configuration can be the full Identity Toolkit project configuration or
an object with `algorithm`, `signerKey`, `saltSeparator`, `rounds` and `memoryCost`.
Both standard and URL-safe base64 export encodings are normalized. Only Firebase
SCRYPT and the providers above are supported. Missing provider subjects, duplicate
UIDs/emails/identities, MFA and tenant accounts require explicit resolution.

```sh
lein run -m tolgraven.provision.supabase.cli dump-auth-import \
  /private/firebase-auth-export.json /private/firebase-auth-config.json \
  /private/supabase-auth-import.sql
psql -v ON_ERROR_STOP=1 -f /private/supabase-auth-import.sql
```

The generator creates a new SQL file with mode `0600` and refuses to overwrite an
existing path. The generated SQL contains password material; do not commit it,
print it or retain it in shared query snippets. Apply it as a trusted database
administrator. It uses one transaction, temporary staging, target table locks,
and ownership checks. UUID, email or provider collisions abort the entire import.
Reruns leave existing accounts, passwords, verification, bans and profiles intact;
identities are inserted only if absent. An account with a changed administrator
profile link is rejected. This command does not reset application tables.

Validate imports in a disposable database with both the application and Supabase
Auth schemas. Run `test/sql/supabase_auth_import_test.py` against that database;
fixtures cover reruns, collisions, ownership and rollback. Configure OAuth
providers and mail delivery separately; imported account verification state is
preserved. Test login without sending unsolicited invitations or reset mail.

### Memory on a shared Coolify server

The web image limits each runtime JVM to a 512 MiB heap, 128 MiB of direct buffers,
and 128 MiB of compiled code. Configure a 1 GiB container memory limit and a
1536 MiB combined memory and swap limit in Coolify, leaving room for metaspace,
threads and native libraries. Runtime JVM limits do not limit Docker builds.
The Dockerfile separately limits the Leiningen/compiler JVM heap to 1536 MiB.
Avoid overlapping builds on the same small host; both production and staging
can otherwise build a preview of the same pull request.

On bux, `/swapfile` supplies 4 GiB of swap and is persisted in `/etc/fstab`.
Swap absorbs brief peaks; it does not replace JVM and container memory bounds.

### One production and one staging runtime

Coolify production previews are disabled. Both site applications use consistent
container names, which stop the current container before its replacement starts.
Build concurrency on bux is limited to one.

Different pull requests otherwise create independent staging previews. The
`tolgraven-runtime-policy` systemd service on bux runs
`scripts/coolify-site-runtime-policy.py` every two seconds to stop superseded
site runtimes and disable their Docker restart policies. It keeps the newest
created production and staging containers and stops any production previews.
Web cleanup is restricted to the two application UUIDs in the script. The
separately scoped `staging_supabase.py` module suspends/resumes tolgraven staging
Supabase when needed; production Supabase, other applications and build helpers
are excluded. See `doc/site-provisioning.md` for lifecycle and readiness details. The guard can take up to two
seconds to detect a different PR preview, plus its shutdown grace period.

Installed paths are `/usr/local/lib/tolgraven/coolify-site-runtime-policy.py`
and `/etc/systemd/system/tolgraven-runtime-policy.service`. Check with
`systemctl status tolgraven-runtime-policy` and
`journalctl -u tolgraven-runtime-policy`. Update the UUIDs if the Coolify
applications are recreated.

### Import safety and image proxy configuration

Full imports require direct Postgres access and atomically replace legacy data,
then replay the private-document migration and post-ID sequence initialization.
Scoped imports upsert changed rows and refuse removal of existing rows: they never
truncate private documents, votes, or unrelated collections. Use dedicated,
reviewed SQL for intentional deletions.

The optional image signer is disabled until `imagor/auth` contains an exact
`allowed-source-hosts` array, a signing `key`, an HTTPS proxy `host`, and
`loader-network-protected: true`. Set that last flag only after configuring
Imagor with `HTTP_LOADER_BLOCK_LOOPBACK_NETWORKS=1`,
`HTTP_LOADER_BLOCK_PRIVATE_NETWORKS=1`,
`HTTP_LOADER_BLOCK_LINK_LOCAL_NETWORKS=1`, `HTTP_LOADER_HTTPS_ONLY=1`, and
`HTTP_LOADER_ALLOWED_SOURCES` matching the approved hosts. These controls must
apply to redirects and DNS resolution at fetch time; signing-side host validation
alone is insufficient. The endpoint accepts only dimensions and optional `fit-in`;
URL-bearing filters are prohibited. Instagram renders original CDN URLs directly.
See https://docs.imagor.net/loader-http/ for loader configuration.
