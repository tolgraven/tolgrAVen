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

## Runtime capacity and deployment scope

Measure actual available memory and workload before provisioning. Local builds
avoid server compiler peaks, but running web and Supabase stacks retain a memory
footprint. The staging lifecycle policy suspends idle stacks. Inspect swap-in/out
and service latency as well as available memory; historical host measurements
are not a site-count limit.

The 3 GiB-per-environment provisioning budget is deliberately conservative, not
a measurement of idle consumption; a new production/staging pair currently
requires 6 GiB available to pass it. Check actual workload and idle capacity
before choosing another server. Swap usage alone is not evidence of current
pressure; inspect swap-in/out and service latency too.

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

## Runtime keys and frontend verification

Provisioning copies `SERVICE_URL_SUPABASEKONG`, `SERVICE_SUPABASEANON_KEY` and
`SERVICE_SUPABASESERVICE_KEY` from each environment's own Coolify Supabase
service into its web app as `SUPABASE_PUBLIC_URL`, `SUPABASE_ANON_KEY` and
`SUPABASE_SERVICE_KEY`. Both normal and PR-preview scopes are updated atomically,
with runtime enabled and build-time disabled. Repeated wiring updates existing
keys and removes duplicate managed preview keys. Conflicting aliases or a
service URL that differs from the manifest fail the operation.

`verify` checks the deployed `/api/supabase/settings` URL and public anon key
against that same instance, and rejects any service-key exposure. The frontend
loads these runtime settings before creating its Supabase client; no privileged
key is embedded in browser assets. A deployment refuses mismatched saved keys.
Staging also gets `SUPABASE_WAIT_FOR_READY=true`; production does not wait.

## On-demand staging on bux

The installed tolgraven runtime policy now manages the existing staging Supabase
service `fqaammdsestcbglokp8ewao0`, in addition to limiting web runtimes. It wakes
the stack when a staging deployment is queued/in progress or a staging web
container is running. After 60 seconds with neither, it runs Compose `stop`.
Volumes, accounts, data, configuration and networks are retained. Production
Supabase is outside this policy's fixed UUID scope.

`make docker` explicitly wakes Supabase and verifies Auth and REST before the
web cutover. A short startup lease protects the gap before queue submission;
a manual deployment recovery record keeps it awake until that deployment is
resolved. Failed Coolify observations never count as idle. On a normal Git or
Coolify rollout, the watcher wakes Supabase and the image entrypoint waits for
Auth and REST before starting Java (up to five minutes). The staging healthcheck
allows this cold start. Reopening a PR therefore also resumes its database.

The automatic suspension policy is currently installed for tolgraven on bux.
Newly provisioned sites get runtime wiring/readiness gating, but must have their
own scoped lifecycle policy installed before claiming automatic suspension.
