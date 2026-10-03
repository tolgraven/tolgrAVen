# Provisioning a complete site in Coolify

Each site gets one Coolify project with `production` and `staging` environments.
Each environment owns a web application and a full Supabase stack: Postgres,
Auth, REST, Realtime, Storage/MinIO, Studio, gateway, pooler, functions, image
proxy and the logging services in Coolify's template. Credentials, Docker
networks and persistent volumes are generated separately for every environment.

The shared infrastructure is Coolify/Traefik and the authenticated S3-backed
image registry on bux. New projects do not copy existing site content, Auth
accounts, runtime credentials, volumes, third-party API keys or sessions.
The application SQL creates its tables, RPCs, RLS/public grants and the empty
`avatars` storage bucket. Those are required schema/configuration, not copied data.

## Create a site

Copy `deploy/coolify/site.example.json` to `.local-wip/my-site.json` and edit:

- A unique project `name`, repository and existing production/staging branches.
- Four distinct HTTPS origins: production web/API and staging web/API.
- Coolify's server/destination UUIDs and the resource server's SSH alias.
- `coolify_ssh_host` if the Coolify controller lives on a different host.
- `builder_image` for the destination server. The loopback registry endpoint
  works on bux only; another server needs an authenticated registry login and
  `registry.bux.tolgraven.se/tolgraven/builder:<dependency-tag>`, or omit this
  field to use the Dockerfile's self-contained builder.

The repository must contain the Supabase migration and Dockerfile used by this
workflow. Until PR #45 is merged, use `codex/strapi-supabase-migration`, not the
older `master`, when provisioning directly from tolgraven/tolgrAVen.

Point DNS at the resource server and configure its HTTPS proxy before starting.
The existing wildcard `*.bux.tolgraven.se` can be used for sites on bux.
The provisioner uses existing root SSH access; it does not create or retain a
Coolify API token. It calls the installed Coolify models/actions, verified against
Coolify 4.3.23. Re-run the transactional probe after upgrading Coolify.

```sh
make provision-plan SITE=.local-wip/my-site.json
python3 scripts/provision-site.py probe .local-wip/my-site.json
make provision-site SITE=.local-wip/my-site.json
```

`plan` is local and read-only. `probe` exercises project, environment, service
and web-app creation in a Coolify database transaction, then rolls it back;
no containers start and returned UUIDs are discarded. `up` prepares resources,
starts Supabase, applies schema transactionally, checks all nine application
tables have RLS, wires runtime-only Supabase variables, deploys the web apps,
waits for those deployments, then checks REST, Auth, homepage and the public
settings endpoint. The service role key stays in Coolify and the backend.

Configuration creation is transactional and retryable. Existing resource names
are accepted only with this provisioner's ownership marker; an interrupted run
does not regenerate credentials. `start` recreates containers through Coolify,
so use `status` before retrying an interrupted `up`. For an already running site,
use the individual phases below rather than restarting the entire stack.

```sh
python3 scripts/provision-site.py prepare .local-wip/my-site.json
python3 scripts/provision-site.py start .local-wip/my-site.json
python3 scripts/provision-site.py schema .local-wip/my-site.json
python3 scripts/provision-site.py wire .local-wip/my-site.json
python3 scripts/provision-site.py deploy .local-wip/my-site.json
python3 scripts/provision-site.py verify .local-wip/my-site.json
```

New web applications have PR previews and automatic deployments disabled, so
there is one explicit staging application. Enable repository automation in
Coolify after configuring the appropriate GitHub integration/webhook. Existing
applications named explicitly by UUID keep their deployment settings.

## Per-site integrations

Production email needs an SMTP account for the site's domain. Add `supabase_env`
to the environment in a **private**, ignored manifest, for example `SMTP_HOST`,
`SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `SMTP_ADMIN_EMAIL`, `SMTP_SENDER_NAME`.
Run `prepare` before the first `start`. The provisioner also accepts the
`MAILER_*`, `ENABLE_EMAIL_*` and `GOTRUE_EXTERNAL_*` configuration families.
Do not put credentials in the committed example. Script rendering is disabled
for manifests containing these configuration values.

Staging has fresh, unconfigured SMTP/OAuth settings by default and cannot send
mail through production's account. Configure a test mailbox/capture service if
email confirmation or recovery is part of the staging test plan. OAuth provider
client IDs/secrets and allowed callback URLs must be configured for each site;
they are external provider registrations and are not database contents.

Other server-only integrations (e.g. AI API keys or a site's media bucket) belong
in that web application's runtime environment. They are deliberately not copied
from tolgraven. Registry S3 storage is shared infrastructure, not site media.

## Capacity and template behavior

Before starting, the command checks actual available RAM on the resource host.
It budgets 3 GiB per new full environment, or 1 GiB for a web app whose database
already runs. A capacity failure leaves the prepared resources stopped; it does
not treat swap as free RAM. This is a startup budget, not a workload guarantee.

Supabase's full stack is substantial. Check `free -h`, `docker stats --no-stream`
and disk space before adding two new instances to bux. Select another registered
Coolify resource server when needed. This command provisions resources on an
existing server; it does not purchase a Hetzner server or create DNS zones.

The template is taken from the installed Coolify version at first creation and
saved with the service; reruns do not silently upgrade it. Coolify 4.3.23's
Supabase template uses the removed `minio/mc` image for bucket initialization.
The provisioner replaces that one image with the template's own MinIO image,
which includes `/usr/bin/mc`; the existing entrypoint/command is preserved.

## Seed tolgraven staging from production

`deploy/coolify/tolgraven-staging.json` explicitly attaches the new Supabase
service to the existing staging project/environment/application, including PR 45.
It does not create or modify production resources. This is an explicit data-copy
operation separate from provisioning empty sites.

The server-side `scripts/seed-staging-supabase.py` helper requires distinct
source/target service UUIDs, the same server/team, a provisioner-owned target in
an environment named `staging`, and empty destination site/Auth tables. It allows
only the identical empty `avatars` bucket created by schema bootstrap, retaining
that bucket on conflict while inserting all other source rows.

```sh
# Run on bux, after schema bootstrap and before wiring the staging web app:
python3 /path/to/seed-staging-supabase.py \
  --source-service e840kco0scs04gkcco44w088 \
  --target-service fqaammdsestcbglokp8ewao0
```

It copies nine site tables, `auth.users`, `auth.identities`, and storage bucket
metadata in a transaction. Password hashes and profile linkage are preserved;
active sessions/refresh tokens are excluded and outstanding account action
tokens are cleared. The data dump stays in memory on the server. Row counts and
all nine public-table content checksums are checked afterward. The helper refuses
to overwrite existing data, and refuses nonempty object storage or MFA/SSO
configuration requiring a more extensive migration. It is a one-time seed,
not a destructive refresh command.

Tolgraven's source currently has no Supabase storage objects, so no object-file
transfer is needed. Existing external media URLs remain as stored in the copied
content. New sites continue to use schema-only provisioning.

## Verified tolgraven staging cutover (2026-10-03)

The staging Supabase service is `fqaammdsestcbglokp8ewao0` in the existing
staging environment. Its API/Studio origin is
https://supabase-staging.bux.tolgraven.se. The deployed staging web application
now returns that origin and its separate anon key from `/api/supabase/settings`.
Both normal and PR-preview runtime scopes were updated; no Supabase key is a
build argument. Production rejects the staging anon key with HTTP 401.

The one-time seed copied 11 posts, 75 comments, 14 profiles, 14 Auth accounts and
identities, plus the remaining site tables and the empty avatar bucket. All nine
site-table checksums matched the source. Disposable live checks passed password
login, owner-document reads, anonymous denial of private documents/email fields,
and image upload/download/delete through the new storage service. Test accounts,
profiles, documents and objects were removed afterward.

A complete new project with two environments, two web apps and two Supabase
stacks also passed the transactional `probe`; zero probe projects remained.
The existing scoped deploy helper stopped the old staging runtime, deployed the
already-built image with the new environment values, checked the HTTP endpoints
and restored its Git build settings. Exactly one staging runtime remained.

Bux had about 2.8 GiB available RAM and 2.5 GiB swap in use after this cutover.
Treat this as a measured snapshot, not spare capacity for another full pair of
Supabase stacks. Use another registered resource server for additional full sites.

The repository's existing GitHub workflow still contains the legacy CapRover
production job and tolgraven's PR-preview webhook. A new site's first deployment
uses this provisioner directly; configure its own branch webhook/CI before
turning on automatic deployment rather than reusing tolgraven's secrets.

`make docker` remains deliberately scoped to tolgraven's existing staging app;
it refuses a checkout whose `origin` is another repository. A new site's deploy
command is `python3 scripts/provision-site.py deploy <site.json>`. `docker-build`
and `docker-push` do not change a Coolify application. Do not reuse the shared
builder alias for a different architecture/toolchain; use a matching dependency
hash tag or the self-contained builder for that server.
