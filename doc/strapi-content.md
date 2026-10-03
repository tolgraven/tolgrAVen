# Strapi content, module dependencies and SSR preparation

The web app's public `:content` comes from the Clojure backend. Static editorial
content has moved out of `db.cljs`; only UI state, routes and options remain there.
On a normal page load the browser requests `/api/content/bootstrap`, installs all
16 public sections, and then mounts the app. A retry screen handles unavailable
content. Supabase continues to own authentication, posts, comments, chat and
private documents.

The backend uses `STRAPI_URL` and `STRAPI_READ_TOKEN` to call the token-protected
CMS bundle. Both variables stay server-side. Responses contain only the public
content contract, use bounded HTTP timeouts, and cache each section for 30 seconds.
Reload after an edit to see it, allowing for this cache. With no CMS configured,
local development uses `resources/content-seed.json` on the backend. A configured
CMS failure returns 503 and never silently restores the original seed.

`tolgraven.content.contract/module-content` is the shared dependency manifest.
Every lazy module's `spec` includes `:content` referencing this manifest, including
an empty vector when it needs no CMS content. The module loader waits for those
sections before running its init function. Concurrent loads share pending
requests; failures release them so retries can succeed. Auto-layout sections have
content sentinels that prefetch within 800 pixels of the viewport. Prefetch only
loads data; it does not initialize a module or cause authenticated writes.

For server-rendering work, `content/for-route!` uses the same declarations to load
shell and route content. Optional `CONTENT_BOOTSTRAP_MODE=route` embeds only that
snapshot, safely serialized in a non-executable JSON script. The frontend installs
it and fetches remaining sections through module init or viewport prefetch. `full`
embeds all sections. The default remains a frontend fetch of the full bundle.
This prepares shared data and hydration state; React server rendering and
`hydrateRoot` are deliberately not implemented in this first integration.

## CMS deployment

The separate repository is `/Users/tol/CODE/WEB/tolgrAVen-strapi`, branch
`codex/coolify-content-api`. Its original content types and components are retained
and completed as 16 editable single types. Existing image paths remain valid;
new uploads take precedence. Seed-on-start creates missing sections only and
preserves edits across restarts.

```sh
python3 scripts/provision-strapi.py prepare deploy/coolify/strapi-staging.json
python3 scripts/provision-strapi.py start deploy/coolify/strapi-staging.json
python3 scripts/provision-strapi.py verify deploy/coolify/strapi-staging.json
python3 scripts/provision-strapi.py wire deploy/coolify/strapi-staging.json
```

Repeat with `strapi-production.json` for production. Each environment has separate
credentials, persistent SQLite and upload volumes, and a 768 MiB container limit
with a 384 MiB Node heap. Small single-process CMS instances do not need another
Postgres server. The services remain available for editing while staging Supabase
sleeps; they are not included in the web-preview cleanup policy. The full `make provision-site` flow accepts `cms_url`, `cms_admin_email` and
`cms_seed_content` in each environment and prepares, starts, verifies and wires
its separate CMS before deploying the web app. See `site.example.json`. A separate
CMS manifest can also extend an existing site. No existing site's CMS database is
cloned automatically.

- Staging admin: https://cms-staging.bux.tolgraven.se/admin
- Production admin: https://cms.bux.tolgraven.se/admin
- Admin email is in each manifest. Coolify stores its generated initial password
  as `CMS_ADMIN_PASSWORD` on that CMS service. It is not shared with the web app.

Build the CMS locally with its `Dockerfile.prod` and publish to the existing Bux
registry. Its dependencies are cached in Docker layers; this does not change the
web app prefab. Change the manifest image reference and rerun prepare/start when
publishing another CMS image. Back up both CMS volumes before migrations.

## Validated rollout (2026-10-04)

PR #45 was squash-merged as `3363e6f`; its preview and staging Supabase stopped
after merge. The content integration is on `codex/strapi-content-pipeline`.
The normal staging site is https://o84wgo08wcs048ss8sokgkgw.bux.tolgraven.se,
using image `tolgraven/site:d0db872ca1f6-20261003221346`. Both CMS services are
healthy and use `tolgraven/strapi:content-v2`. Production CMS settings are wired,
but this content integration has only been deployed to the staging web app.

Live checks confirmed all 16 sections through the backend, CV-only selection,
400 for unknown sections, correct staging Supabase public settings, and rendered
homepage content. Local validation passed 56 backend tests (331 assertions),
32 browser tests (186 assertions), 42 deployment tests, CMS contract round trips,
TypeScript checking, and optimized Docker builds. A CMS admin edit survived a
local restart before restoring the seeded value.

The live CMS containers used approximately 218 and 220 MiB at idle. These are
observations, not peak capacity guarantees; each retains its 768 MiB limit.
Strapi was upgraded to 5.56.0 and compatible dependency fixes applied. Its audit
still reports upstream transitive advisories (40 high, 9 moderate, 1 low; zero
critical at validation time). The CMS repository has no configured Git remote,
so its `codex/coolify-content-api` commits are local; the deployed image is
persisted in the S3-backed registry.

## Service failures

Supabase initialization, session restoration, snapshot reads and live-update
failures now produce webpage diagnostics and persistent service notices. Snapshot
failures retry automatically and preserve previously loaded data. A disconnected
or stalled live channel falls back to an HTTP snapshot; it no longer gates all
initial content. Settings, session and snapshot requests have bounded waits.
Strapi bootstrap and deferred/prefetched content failures are logged and expose
retry controls. A malformed embedded snapshot can recover through a fresh fetch.
Repeated failures during one outage do not flood the log; successful recovery
clears its notice. Raw upstream error bodies and credentials are never included.
The browser regression suite covers 38 tests / 207 assertions, including visible
notices and retry recovery. Live CV/blog rendering and the previous deployment's
single-runtime/configuration cleanup were verified after browser access recovered.
