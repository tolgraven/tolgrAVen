# Shared declarations and contracts

Keep this guide current. Shared platform data shapes, schema infrastructure,
query constructors and normalization belong here so CLJ and CLJS use the same
definition. Portable module data contracts and page specs live beside their frontend
implementation in `src/frontend/tolgraven/modules/<module>/`, also on the JVM source path.

- Module `pages.cljc` exports a native Reitit route tree and may contain nested or
  multiple pages. Do not require module implementation namespaces from it.
- Public data plans are adapter-independent. Server reads and managed browser
  subscriptions consume the same filters, projections, batching and row shape.
- Preserve EDN keys/types in persistence contracts. JSON is converted and normalized
  only at transport boundaries. Never include credentials in public snapshots.
- `ssr/contract_schema.cljc` owns paired renderer responses and module/export
  metadata; the browser and JVM consume the same snapshot contract.
- Keep schemas composable by module/app-db section; avoid a second global inventory
  of declarations already owned by modules. Tests should consume the same schemas.
- Component/form/event/browser-state contracts live beside their frontend owner in
  `.cljs`. Use `.cljc` only for real consumers on both platforms. Do not make
  browser contracts portable merely to include them in a JVM inventory test.
- Reader conditionals must work for CLJ tooling/Codox as well as browser and Node
  Shadow builds. Do not make JVM documentation scan browser-only namespaces.

- `schema/dsl.cljc` expands optional-key and nullable-type shorthand without
  importing Malli. Validation and JVM/browser coercion adapters expand forms
  before interpretation; schema composition stays data. See `doc/schemas.md`.
- `schema/declarations.cljc` defines declaration shapes; module-local schemas own
  domain fields. `schema/app_db.cljc` supplies generic composition/change-validation helpers;
  frontend `validation/schema.cljs` assembles browser/Node sections. Keep coverage
  explicit and retain open maps where migration is incomplete.
- `schema/http.cljc` is shared by page and API routers; use those typed parameters
  in handlers/controllers and use the same schemas for accepted/rejected fixtures.
  Upstream service path allowlists require full-string matches before credentials
  or transport are acquired; Malli `:re` alone matches substrings.
  Its JVM adapter supports API documentation; CLJS uses the smaller page coercion
  adapter. Shared schema composition emits native Malli `[:merge ...]` data,
  without importing the interpreter into the production browser shell.
  `validation.cljc` is a stable boundary; its engine/registry are eager on JVM,
  development and Node SSR, and installed by the browser `:coercion` module.

- Define meaningful new contracts alongside their owner and use them across both
  runtimes. CMS bundles and normalized app-db/provider content have different
  shapes. SQL projections include selected NULL columns. Keep raw transport,
  normalized record and component presentation contracts distinct where needed.

- `defpage` enables an optional `:container` layout capability outside its error
  boundary. It owns a stable root across loading/content/error states; the site
  shell opts out with `:container false`.

- `loader/style_catalog.cljc` bakes literal module `:styles` and bundle dependencies
  into both runtimes at compile time, including literal `:ssr-styles` policy.
  Initial head paths exclude deferred owners; runtime paths retain every dependency.
  Declaration macros track the module entry
  resources and icon-owning component sources in Shadow so metadata changes
  invalidate cached inventories. `build/icons.cljc` reads literal `:icons` data
  without evaluating browser code; common icons satisfy feature dependencies. JVM
  expansion stays independent of the compiler. Packaged servers do not read a source tree.
  Fingerprint each declared stylesheet once; shared dependencies retain one URL
  across module manifests. Reject colliding output names rather than merging them.
- `components/image/sources.cljc` shares responsive candidates/dimensions with
  server preloads. The catalog macros register Shadow resource dependencies so
  incremental builds refresh changed EDN catalogs. Regenerate sized
  AVIF/WebP files with `bb images:responsive`.
- `components/video/sources.cljc` bakes the responsive video catalog into source
  declarations. Native media queries select smaller renditions; preserve full-size fallbacks.
- Module declarations may supply `:install` for code-owned setup. Browser loading
  awaits it before publishing readiness; Node adapters keep browser disk/cache
  installation inert. Keep this lifecycle distinct from managed data activation.

- Thin build adapters may use `.cljc` solely for Shadow reader features (`:browser`,
  `:ssr`, `:dev`); `.cljs` cannot contain reader conditionals. Keep these separate
  from shared JVM contracts and do not infer a server renderer from the extension.

- `components/oembed/contract.cljc` owns the recognized player endpoint allowlist
  and normalized response consumed by both the JVM proxy and browser frame.

- Diagnostics adapters use `:dev` reader boundaries so source browsers, source-map
  decoding, received-value details and profiling registrations leave production
  and Node graphs. Public validation issue maps remain value-redacting; bounded
  development details may use metadata without changing transport contracts.

The thin `ajax/time.cljc` Shadow adapter lives beside its browser consumer and
retains Java-time Transit codecs only under `:dev`. Production uses standard Transit;
server format handlers retain their native JVM Java-time support.

- `defc` accepts ordered feature-vector first arguments on spec-capable instances,
  expanding them to `{:features ...}` before validation and rendering. Map specs
  retain explicit `:features` alongside props, containers and domain fields.
  Explicit `:spec false` and ordinary non-spec primitives retain the direct Reagent
  path; per-instance ownership and protected page/data contracts are
  documented in `doc/components.md` and implemented by the browser/Node runtime.
  Component `:state` may be an options map or a pure function of component arguments
  returning that map; browser lifecycle effects own its initialization.

- `supabase/catalog.cljc` bakes module-owned portable `:storage` declarations via
  existing module discovery. Keys/projections/JSONB columns have one owner;
  query/realtime and JVM seed tooling consume that data. Track portable sources
  with module entries in Shadow. See `doc/supabase-tables.md`.

- Source-graph capability checks read one fresh settings snapshot per build;
  never reacquire or validate the configuration inside the per-source loop.
- Site capabilities are resolved from file defaults plus optional CMS overrides before build/provisioning.
  Module specs own storage/content/pages/server declarations; disabled owners stay out of artifacts.
  Read `doc/site-capabilities.md` for build snapshots, dependency validation and retained-data tests.

- Automatically eligible large catalog entries default to progressive display;
  the shared dimensions/bytes predicate excludes cheap originals. Explicit
  `:progressive? false` opts out. Catalogued WebP/AVIF originals share canonical
  stems with JPEG/PNG renditions. Final sources retain the complete responsive set;
  preview acquisition is native lazy markup, suppressed on history returns.

- `media/schema.cljc` owns public rendition/asset, upload/status and library contracts.
  `media/query.cljc` builds shared public manifest queries for page plans and managed
  browser bindings. Post `:image-assets` and CMS section sidecars carry paired SSR
  metadata; private job/source/lease fields must not enter public projections.

- `build/authoring.clj` extracts canonical identities, docstrings and signatures of selected
  public render components without evaluating source. Rich help is editor-owned;
  published lookup retains only lazy owners. Track source resources and prune disabled owners.

- `markdown/components.cljc` owns portable quoted component attributes and data
  prefixes. Browser composition and media source lookup share this grammar;
  ready server manifests determine rendition metadata, never authored asset objects.
