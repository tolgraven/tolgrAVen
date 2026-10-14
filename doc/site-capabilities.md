# Site capabilities

`resources/site.edn` declares file defaults. `bb site:resolve` optionally reads
`STRAPI_URL/api/site-config`, authenticated with the existing server-only
`STRAPI_READ_TOKEN`, and creates an ignored, sanitized `site.overrides.edn` snapshot.
The CMS overrides individual module options and page flags; unspecified values
retain file defaults. Omitted options default to enabled. Missing CMS configuration
(404) uses file defaults. Authentication, network, invalid config and redirect
failures stop the build. Credentials and webhook settings never enter the snapshot.

```clojure
{:modules {:blog {:comments false}
           :strava {:enabled false}}
 :pages {:about false}}
```

Module IDs come from literal `:id` in discovered `modules/*/module.cljs`, including
`:test` for the experiments directory. The shell and route coercion are required.
Dependencies are explicit: disabling a required module or service-config feature
fails rather than silently enabling it. Unknown modules/options fail. Page IDs
come from route `:name`; disabling a page removes its subtree from both routers.
Disabling all pages in a module does not disable that module's non-page capabilities;
use its module toggle to remove its code and APIs.

Run resolution before local compilation/packaging. Docker resolves once in its
shared source stage, so browser, Node, JVM packaging and generated documentation
use one snapshot. Coolify wiring enables BuildKit secrets for `STRAPI_URL` and
`STRAPI_READ_TOKEN` and disables build cache so same-source redeploys see CMS edits.
These secrets must use BuildKit, never ordinary build arguments. With no configured
CMS, builds use file defaults and make no network request. Restart Shadow watches
when changing enabled modules, including after refreshing CMS selections.

## Owners and artifacts

Module specs expose portable `:storage`, `:content`, `:pages` and `:server` data.
`:server` names its route adapter, optional extra SQL, module dependencies and
`:requires-features`. Storage defines table keys, projections, ordering, JSONB,
seed ownership, SQL resources and optional features. There is no parallel table
inventory in the backend or provisioner. Page and content declarations do not
load browser implementations on the JVM.

Shadow composes selected bundles and reader features, checks that disabled owner
sources never enter browser/SSR graphs, and removes old output when selection or
build mode changes. Conditional shared adapters remove their domain schemas and
bindings too. JVM AOT and jar exclusions omit disabled feature namespaces, SQL and
feature styles. `lein with-profile uberjar,provided docs` stages selected sources
before Codox enumerates them. Sources for disabled modules and unfinished examples remain in the
repository for subsequent re-enabling. Superseded monolithic SQL files are retained
as frozen reference snapshots and excluded from packaged runtime resources.

Supabase is an ordinary `:supabase` Shadow module. Its npm SDK and transport
are acquired by the existing managed data bindings or account actions, not by
site startup or a global script. Routes with no such consumers do not request
that chunk. Disable the module alongside its storage consumers to omit its code
through the same discovery and pruning as other modules. Storage declarations
validate the dependency and export it to the CMS, including optional main
services/documents. The legacy vendored UMD copy remains a reference artifact
and is excluded from jars.

`bb site:manifest` exports the resolved configuration, all available module toggles,
route names, selected content fields/tables and SQL resources. `bb site:manifest sql`
exports transactional provisioning input. The same schema plan runs in the JVM
provisioner. Runtime `:supabase :modules` may narrow seed tooling further; it cannot
add capabilities absent from the compiled site.

## Strapi and deployment

The companion CMS consumes `CMS_SITE_MANIFEST`, generated from the same declarations.
Its Site features and pages single type provides boolean controls for every optional
module and page, including disabled modules. Core features remain required. It keeps
content-type schemas installed, hides disabled types from the editor, and filters
them out of the authenticated content bundle. Keeping schemas installed prevents
Strapi's schema synchronization from deleting saved content. A CMS restart/redeploy
applies visibility changes; the build endpoint reads the latest saved selections.

The CMS Publish site changes page has Update editor and Publish site actions. These
require a Strapi super administrator, independent of public users or the server read
token. Provisioned webhook URLs/tokens use private, non-searchable fields and server
runtime environment variables. Public/config/content responses never return them.
Actions forward credentials only to their exact provisioned HTTPS URL, reject
redirects, and return acceptance without claiming deployment has completed.

CMS `prepare` automatically creates separate deploy-only Coolify API credentials
for Publish site (the web application) and Update editor (the CMS service). It derives
the HTTPS webhook origin from Coolify's instance settings, associates credentials
with an existing owner/admin of the resource team, and copies each URL/token into
the CMS's `CMS_REBUILD_WEBHOOK_*` / `CMS_REPROVISION_WEBHOOK_*` runtime variables.
Strapi bootstrap copies them into private site-config fields for its authenticated
admin actions. Credentials never enter build snapshots, browser bundles or script
output. Coolify deploy permission is team-wide; the CMS action fixes the resource URL.

Enable Coolify API access and configure its public HTTPS origin before `prepare`.
An API IP allowlist must also admit the CMS host. The provisioner does not change
these instance-wide controls. Managed tokens have no explicit expiration to keep
unattended admin actions working; revoke them in Coolify's API Tokens page when
needed. `prepare` reuses valid tokens and replaces revoked, expired or otherwise
invalid managed tokens atomically. Each environment gets its own credentials.
Run `start` after preparing an existing CMS to load the updated environment.

Optional `cms_webhooks` per environment override either action with an HTTPS `url`
and `token` (standalone CMS manifests use `webhooks`). An explicit override replaces
that action's managed token. Use `reprovision` to target an infrastructure runner
when configuration changes require new services or schema migrations. The default
Update editor redeploys Strapi and preserves its data; it does not run infrastructure
provisioning. Save features, update the editor, fill newly visible content, then
publish the site. The future site admin UI can invoke the same protected action
contract; it does not need Coolify access or raw secrets.

Operations resolve the same optional CMS override snapshot before selecting their
schema. Run environment-specific provisioning with that environment's existing
`STRAPI_URL` and `STRAPI_READ_TOKEN`; do not reuse a production snapshot for staging.
The infrastructure runner must apply the resolved schema before serving a build
that adds database features. A plain deploy webhook does not create a previously
absent Supabase service; use the configured reprovision runner for that transition.
No live infrastructure or production data is changed by building artifacts.

Supabase is skipped for an initially storage-free site. Existing services/volumes
are retained when the last storage feature is disabled; the schema operation still
uses that retained database to revoke feature access. Disabled tables and rows stay
installed, while column/table grants, RPC execution and realtime publication are
removed. Avatar reads become private when users are disabled. Re-enabling reapplies
owned grants/functions/publication/bucket access without deleting rows.

## Verification

In an idle isolated worktree, `python3 test/site/matrix.py --compile` checks default,
posts without comments, no blog, anonymous, minimal storage-free, no home and disabled
page configurations. `--release` checks production outputs instead. `PGHOST`,
`PGPORT` and `PGUSER` enable disposable fresh PostgreSQL installations for every case.
`python3 test/site/package.py` checks actual AOT jars and generated docs for two
slim configurations. Artifacts/logs are under `target/site-matrix/`; the original site file is restored.
Run `python3 test/site/retention.py` with the same PostgreSQL connection to verify
actual grants/RPC/publication and retained disable/re-enable data. These metadata
checks do not establish live Supabase Auth or realtime delivery.

The resolver test uses an HTTP fixture and dummy credential. The companion CMS's
`test/site-config-live.py` uses a disposable SQLite database, real admin authentication
and the exported manifest to check secret isolation, visibility and retained data.

## Module declarations and optional code

Keep build declarations inside each module's `spec`, alongside its content,
exports and lifecycle. `:bundle/depends-on` is this project's input to Shadow's
hard `:depends-on` graph; it is not a built-in namespace feature. It defaults to
`#{:main}`. Declare another bundle here only when the implementation imports its
code. Optional use belongs behind that owner's lightweight interface and the
existing managed loader; a declaration must not turn optional UI into a hydration
prerequisite. Blog, Chat and Search render Markdown immediately and acquire Link
Preview only for external-link candidates. Disabled previews select plain Markdown.

A missing `:id` is the keyword naming the parent of `.module`, for example
`website.modules.maps.module` becomes `:maps`. Explicit IDs remain useful when
names differ. Missing styles use an existing generated conventional stylesheet or matching SCSS entry;
see [styles.md](styles.md). Early packaging and compiler discovery share these
source-only defaults, and browser/SSR adapters add the catalog identity before
runtime validation. `:site/owner` also belongs in `spec`, for child capabilities.
Namespace maps with these custom build declarations are rejected.

`:content` supplies the module's derived CMS dependency plans before acquisition.
Do not repeat those plans in `:depends`; reserve that field for additional managed
resources. The eager Main spec retains its data declaration because boot owns it
outside ordinary module loading.
