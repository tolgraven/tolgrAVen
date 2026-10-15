# Strapi content, module dependencies and SSR preparation

## In-site editing

Administrators open **Edit site content** from the account panel. The same panel
links to **Strapi admin**, where site configuration, content structure, section
selection and ordering remain managed. Ordinary visitors render native headings,
paragraphs, spans and responsive pictures. In edit mode, text fields retain their
typography and pictures retain their pixels with a corner replacement control.
The toolbar stays available after closing the account panel. **Save changes**
publishes the changed fields; **Discard changes** restores their loaded values.
Failed saves retain the draft and show the actual recovery message. Conflicts
install the latest public value while keeping the draft; **Use latest value**
discards only that field's draft so it can be reviewed and edited again.

`:content/view` wraps the existing managed content subscription, preserving its
Clojure values and adding field path/mode metadata to maps and vectors. Renderers
use `website.content.fields` for text and preserve metadata when merging media
props. The lazy `content-editor` module owns account-bound re-frame drafts,
authenticated request effects and existing media upload/status events. These
drafts remain in memory; reloading discards unsaved edits. Signing out or changing
accounts disables editing and prevents old asynchronous completions from applying.

`GET /api/content/editor` returns only the administrator's public admin link and
write availability. `PUT /api/content/field` verifies the session with Auth and
checks the current `auth_roles` record before using the separate server-private
`STRAPI_WRITE_TOKEN`. The CMS counterpart accepts `CMS_WRITE_TOKEN`; a read token
cannot write. Provisioning's prepare/wire flow creates and wires that token,
including its Compose environment reference when upgrading an existing CMS.
An optional `STRAPI_ADMIN_URL` sets a different public admin origin. When write
configuration is missing, public content continues normally and the account
panel explains why inline editing is unavailable.

The CMS endpoint allows specific text/image leaves, compares their loaded values,
serializes section writes inside a database transaction, and updates only the owning stored field. Component IDs,
unrelated properties and media relations survive edits. Replacing an image clears
the old relation so it cannot override the replacement source. The site's existing
durable media pipeline converts/uploads still images before a replacement becomes
a saveable draft. Enriched pictures keep their original CMS source in `:cms-src`
for conflict checks; it never enters native image markup. Saved sections update
the shared cache/shell immediately and win over older pending refreshes.
They also expire cached SSR HTML immediately; older renders cannot overwrite the
new publication's page cache. Existing browser history returns retain their saved
view state; a fresh network document uses the newly published content.

The Strapi image must include `/api/site-content/field` before enabling the web
write token. Disabling that token turns off inline writes without changing public
content or requiring a content migration. The browser saves fields sequentially
and installs each response before the next write, preventing old section responses
from replacing later saves.

CV timeline rows expose **Add entry** in edit mode. Choose **Regular box** for a
role, course or other individual entry; choose **Timeline container** for a
grouping. Containers have their own title, description and main logo, plus child
logo/color tiles. Opening one shows a mini timeline using the same box types and
date axis as the main timeline. Every new container starts with a regular
**New entry** child that can be edited immediately, including a first detail line
when its body is empty. **Zoom back out**, Escape or
the surrounding backdrop returns to the containing timeline.

Expanded boxes stay centered in the viewport. The owned portal measures the
clicked box and animates from its bounds, then measures its current bounds again
on closing. Nested surfaces retain their parent underneath. Reduced-motion
preferences skip the motion. A regular box opens its own details; only a
container opens a child timeline. Nesting is bounded to six levels.

`POST /api/content/cv-entry` uses the same fresh administrator check and private
write token as field writes. It accepts bounded dates/text, a regular/container
kind and a selected parent chain. The CMS verifies parent identities and the
original entry count under the section transaction. Reordering or an uncertain
success response produces a conflict rather than creating an entry in another
parent or repeating it. Other inline drafts must be saved or discarded first;
inputs remain disabled while creation is pending.

Deploy the companion CMS schema/endpoints before the web write token. Root CV
entries remain Strapi components; bounded JSON holds their nested children without
recursive component definitions. Existing entries default
to regular boxes and their component/media IDs are preserved. Disabling the write
token stops editing while keeping all saved content and containers readable.
For rollback, disable writes while retaining the upgraded CMS schema. Back up the
CMS database before removing its container fields; an older schema can discard
persisted child timelines.

The web app's public `:content` comes from the Clojure backend. Static editorial
content has moved out of `db.cljs`; only UI state, routes and options remain there.
On a client-rendered page load the browser installs only the shared shell
content before mounting the app. Route and feature modules acquire their
declared sections through their own boundaries when activated. A retry screen
handles unavailable shell content; module boundaries handle their own failures.
Supabase continues to own authentication, posts, comments, chat and private documents.

The backend uses `STRAPI_URL` and `STRAPI_READ_TOKEN` to call the token-protected
CMS bundle. Both variables stay server-side. Responses contain only the public
content contract, use bounded HTTP timeouts, and cache each section for 30 seconds.
Concurrent readers share an in-flight read for identical missing-section sets. Cache locking never
covers upstream/media I/O, so an unavailable section cannot stall unrelated
content requests. Failed reads release their waiters for retry.
Reload after an edit to see it, allowing for this cache. With no CMS configured,
local development uses `resources/content-seed.json` on the backend. A configured
CMS failure returns 503 and never silently restores the original seed.

`website.content.contract/module-content` is the shared dependency manifest.
A lazy module's `spec` includes `:content` referencing this manifest, including
an empty vector when it needs no CMS content. The module loader waits for those
sections before running its init function. `github-settings` and `codex-profile`
are not published by the CMS yet, so their modules declare no CMS dependencies.
Bootstrap, module acquisition and content subscriptions omit those keys. The
GitHub configuration prompt and Codex unavailable state remain usable; their
schemas and readers are retained for future publication. Concurrent loads share
pending requests. A failed shared bundle is retried once per section so one
unavailable section only rejects its own consumers; successful content remains
cached. A single failed section exposes the existing retry notice. During a CMS
outage this isolation adds at most one request per queued section, with the same
bounded timeout. The CMS managed adapter allows 31 seconds for both 15-second
transport attempts and queue settlement; explicit dependency timeouts override
that budget. Other sources keep their 15-second default. Failures release pending entries so retries can succeed. Auto-layout sections have
content sentinels that prefetch within 800 pixels of the viewport. Prefetch only
loads data; it does not initialize a module or cause authenticated writes.

For server-rendering work, `content/for-route!` uses the same declarations to load
shell and route content. Optional `CONTENT_BOOTSTRAP_MODE=route` embeds only that
snapshot, safely serialized in a non-executable JSON script. The frontend installs
it and fetches remaining sections through module init or viewport prefetch. `full`
embeds all sections and therefore requires all declared CMS sections to exist.
The default frontend acquisition loads only shared shell content. Route/feature
content failures remain within their owning modules.
This prepares shared data and hydration state; React server rendering and
`hydrateRoot` are deliberately not implemented in this first integration.

## CMS deployment

The separate repository is `/Users/tol/CODE/WEB/tolgrAVen-strapi`, branch
`codex/coolify-content-api`. Its original content types and components are retained
and completed as 16 editable single types. Existing image paths remain valid;
new uploads take precedence. Seed-on-start creates missing sections only and
preserves edits across restarts.

```sh
python3 scripts/ops/provision-strapi.py prepare deploy/coolify/strapi-staging.json
python3 scripts/ops/provision-strapi.py start deploy/coolify/strapi-staging.json
python3 scripts/ops/provision-strapi.py verify deploy/coolify/strapi-staging.json
python3 scripts/ops/provision-strapi.py wire deploy/coolify/strapi-staging.json
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


## Local Docker cleanup

Successful `make docker-build`, `make docker-push`, `make docker` and prefab
publishing prune obsolete local tolgraven image tags automatically. Keep the
latest two application images, the newest CMS image, the current dependency
prefab, the last explicitly built image and every image referenced by a running
or stopped container. Unrelated project tags are not removed. Cleanup never
forces deletion and does not remove volumes or S3 registry images.

`make docker-clean` also removes dangling images and asks BuildKit to reduce
unused build cache toward 4 GB, retaining active cache. Automatic build cleanup
keeps the build cache intact so subsequent local builds remain fast.

## Startup readiness

HTTP readiness requires the shared shell sections. The existing scheduled
refresh worker then warms module-owned startup headings independently; failure
keeps that heading's last successful value and does not prevent healthy headings
from warming. A cold route can acquire missing content through its normal
managed dependency. Client startup likewise gates only on shell content; route
modules own loading/error/retry through their existing boundaries.
