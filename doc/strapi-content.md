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
