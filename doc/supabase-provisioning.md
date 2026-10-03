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

The seven imported tables have RLS enabled; the new private vote ledger does too. Anonymous and authenticated roles have
column-level SELECT access to the public post, comment, chat, and profile fields.
Email, raw import JSON, vote history, service configuration, roles, and generic
documents are not public. Anonymous SELECT was verified against the live database:
11 posts, 75 comments, and 7 profiles remain readable; email, service configuration,
and generic documents are denied. The same grants are included in `schema.sql`.
No content rows were changed by this access update. Security Advisor reports zero
errors and zero warnings; its four informational notices are the deliberately
policy-free private tables (`auth_roles`, `service_configs`, `store_documents`,
`comment_votes`).
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

Create/confirm Supabase Auth accounts and link existing owners to the imported
Firebase profile IDs, then verify login in the deployed application. Firebase
authentication is initialized only when the Firebase provider is selected.
Chat, comment creation/editing, and voting now use dedicated authenticated
transactional operations. Blog authoring still uses the administrator compatibility
bridge. Move service integration credentials behind server endpoints before
removing their existing browser-side store reads. Strapi content migration remains
separate. This branch is not a completed production cutover.

Validation: `lein test tolgraven.supabase-shape-test` and
`lein run -m shadow.cljs.devtools.cli compile app`.


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

Verified locally: session rejection, server token validation, ignoring forged
user metadata, ownership-scoped profile writes, protected profile fields, and
linkage preconditions. Live signup/login has not been exercised. Firebase Auth accounts are now
imported as described below; provider and SMTP configuration still determine
which login and email confirmation flows are available. Chat,
comment and vote writes use the authenticated endpoints described below;
generic blog authoring remains behind the administrator compatibility endpoint.


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
refreshes queries/profiles after successful writes, and clears pending state when
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

### Verification (2026-10-03)

The operations and ledger/index are installed on the supplied live Supabase
instance. `test/sql/supabase_operations_test.sql` passed locally and live; all
fixtures and test changes rolled back. It checks ownership, canonical reply paths,
counters, vote retries/reversal/removal, legacy score baselines, forced transaction
failure, private history access and RPC permissions. Before the Auth import below, the live inventory was
11 posts, 75 comments, 7 profiles and 24 chat messages, with zero Auth accounts,
native votes or test profiles. Refreshed Security Advisor reports zero errors and
zero warnings, with four intentional private-table informational notices.

A disposable local Postgres instance also passed 108 concurrent calls covering
repeated votes, 20 distinct voters, reciprocal votes between authors, and
simultaneous replies/votes. Scores, karma and comment counters matched the expected
values, with no lost increments or deadlocks.

Application checks passed: 24 tests with 147 assertions, plus frontend compilation.

Commands:

```sh
lein with-profile +test test tolgraven.supabase-shape-test tolgraven.supabase-auth-test tolgraven.supabase-operations-test tolgraven.handler-test
lein run -m shadow.cljs.devtools.cli compile app
psql -v ON_ERROR_STOP=1 -f test/sql/supabase_operations_test.sql
```

The handler tests need a nonempty local `test-config.edn` (for example `{:test true}`);
the tracked test resource currently contains only `{}`. Frontend compilation
retains the two pre-existing `rrb-vector` dependency warnings. Application
changes have not been pushed or deployed; real account login remains unverified.


## Firebase Auth account import (2026-10-03)

The supplied live instance now has **14 Auth accounts and 14 identities**:
8 email/password, 4 Google, 1 GitHub and 1 Facebook. All 8 Firebase passwords were
preserved in Supabase's native `$fbscrypt$` format, with the original salt, signer
key, separator and cost parameters. The installed Auth version is v2.174.0 and
supports this format. No plaintext passwords, invitations or reset emails were
used. The 2 verified emails remain verified; the 8 password accounts remain
unverified. All source accounts were enabled. Four OAuth accounts have no email;
their provider subjects are preserved without invented emails or anonymous flags.

Each Auth UUID is deterministic from the Firebase project and UID. Trusted
`app_metadata.site_user_id` points to the Firebase UID, keeping existing content
ownership and roles. All 7 original profile rows were compared in full and remain
unchanged; 7 missing profiles were added. Current totals are 14 profiles, 11 posts,
75 comments and 24 chat messages. Verification compared every imported password,
account metadata, verification flag, ban, creation/sign-in timestamp, provider
subject and identity metadata against the export.

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

### Validation and remaining setup

The live import first passed in a rolled-back transaction against the actual
Auth schema. Backend checks passed: 29 tests, 192 assertions. The disposable local
Postgres checks cover identity insertion, OAuth without email, profile preservation,
reruns after password/verification/ban changes, ownership collisions and rollback:

```sh
lein with-profile +test test tolgraven.supabase-shape-test tolgraven.supabase-auth-test tolgraven.supabase-auth-import-test tolgraven.supabase-operations-test tolgraven.handler-test
python3 test/sql/supabase_auth_import_test.py -h /tmp -p 55432 -U postgres -d postgres
```

The SQL integration test requires a disposable database with the application and
Supabase Auth schemas. It refuses existing fixture IDs and removes its fixtures.
Local checks using the installed Auth version's crypto implementation passed the
upstream correct/wrong-password fixture and parsed all 8 imported hashes. A real
user login has not been exercised. Automatic approval review rejected an extra
live test account, so password fixture verification remained local.

The live Auth settings enable Google and email login. GitHub and Facebook are
disabled and require their provider credentials/configuration before login. Email
autoconfirm is disabled; the 8 unverified password accounts require email
confirmation through a working mail configuration. No email delivery was tested
or triggered. The application migration branch remains unpushed and undeployed.
