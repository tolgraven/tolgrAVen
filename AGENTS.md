# Repository Guidelines

Keep this file and every scoped `AGENTS.md` up to date in the same change that
alters the architecture, build workflow, or contracts they describe. Keep
instructions concise; detailed schemas, examples and user guides belong in `doc/`.
Do not retain completed investigation diaries or historical test counts as current guidance.

## Current architecture
- Reagent 2 / React 19 components use `defc`; full pages use `defpage` and always have an error boundary. Application re-frame calls go through `tolgraven.react`.
- Module-local `pages.cljc` files contain native Reitit route trees. Module specs expose these declarations; browser and server routers compose them.
- JVM adapters acquire public data with snapshot-owned, bounded Supabase REST
  connections; the eager Shadow `:ssr` Node build renders the same page components as the browser. `:ssr` in config controls streaming and worker settings.
- Module references activate near the viewport by default; hidden panels use
  explicit intent. Completed SSR module views retain their DOM until selective
  hydration; module CSS is available for that static view.
- Cold/expired SSR streams a component-derived skeleton then the completed page.
  Fresh cached SSR skips the skeleton (`:ssr :cache-ttl-ms`, default one hour).
  Hydration preserves the existing DOM and does not replay entrances.
- Analytics initialization and script acquisition belong to the browser lifecycle
  after hydration and page readiness; SSR emits no analytics markup or bootstrap.
- After hydration, navigation is entirely SPA: commit the destination immediately, then acquire code/data through shared bindings. Related blog routes retain their shell.
- `boot.cljs` owns ordered document listeners, committed UI setup and deferred
  background hosts. Register browser listeners through effects with an owner and
  cleanup; importing a namespace must not install document listeners.
- Local external returns may use a service-worker document pairing rendered HTML with exact EDN state/content; install that state before hydration. The `:return-worker` build is part of the deployment.
- Read `src/frontend/tolgraven/AGENTS.md`, `src/cljc/tolgraven/AGENTS.md`, `src/backend/tolgraven/AGENTS.md`, and `test/AGENTS.md` when touching those areas.


## Project Structure & Module Organization
- `src/backend`, `src/frontend`, `src/cljc`: Backend code, frontend code, and shared CLJC infrastructure. Feature implementations and their portable declarations live in `src/frontend/tolgraven/modules/<module>/`.
- `src/frontend/tolgraven/components/`: reusable rendered UI and site shell;
  `component/` contains the declaration/lifecycle runtime.
- `experiments/clj`: preserved JVM prototypes, compiled only with `:experiments`.
- `resources/`: runtime assets and public output; SCSS lives in `resources/scss` and builds into `resources/public/css/tolgraven`.
- `test/clj`, `test/cljs`: backend and frontend tests.
- `env/`: environment-specific source/resources (dev/test/prod).
- `resources/supabase/`: schema, native operations and account import SQL.
- `scripts/`: grouped Babashka build/media/dev/test tasks and explicit operations adapters; `bb.edn` is the task catalog. See `doc/tooling.md`.
- `config/`: private local overrides and tracked examples; environment classpath defaults remain in `env/`.
- `doc/`: project documentation.

## Shared schemas
- Browser releases use Malli's custom registry and a frontend Reitit coercion
  adapter; keep Swagger/OpenAPI generation on the JVM. Extend the registry for
  new schema types and verify the browser contract suite. See `doc/schemas.md`.
- Define Malli contracts beside their consumers. Use `.cljs` for browser/Node-only
  state and component contracts; use `.cljc` only for actual JVM/CLJS consumers (or thin Shadow target-feature adapters). Reuse them in tests,
  Reitit page/API parameters, and runtime validation; see `doc/schemas.md`.
- Schema meaningful new/changed CLJ and CLJS data and public boundaries as part of
  the feature. Derive contracts from actual writers/readers, including loading,
  empty, error and restored states. Trivial private view signatures may stay plain.
- Validate parent-supplied view data too: `defc` supports inline `[arg :- schema]`,
  `:spec-schema` and `:args-schema`, independently of subscription data. Extend/compose common
  component, instance-spec, module and page schemas instead of duplicating them.
- Extend app-db by section and expose module-owned additions through `:db-schema`.
  Do not validate the entire db on every event; the shared interceptor checks
  changed sections and rejects invalid transactions before effects run.
- Internal checks follow `:validation {:enabled ...}` / `VALIDATION_ENABLED` and
  default to development only. Public input coercion stays enabled in production.
- Read typed request/controller parameters from `:parameters`; report paths and
  constraints without logging raw values, credentials, or event arguments.

## Build, Test, and Development Commands
- `lein repl`: start the HTTP server and Shadow CLJS REPL (see `README.md`).
- Browser builds discover `modules/*/module.cljs` through `tolgraven.build.browser/process`;
  the literal spec `:id` owns the bundle ID and namespace `:bundle/depends-on`
  declares extra code dependencies. Runtime inventories track declaration resources
  for incremental metadata updates. Restart the browser watch after adding/removing
  module entry files; the runtime loadable map comes from the same discovery.
- Production `:app` uses content-hashed chunk names; server bundle/preload paths
  come from Shadow's output manifest. Development keeps plain watched filenames.
- Landing routes use the lazy `:home` module; keep its views and styles out of
  bootstrap/router imports. Node SSR uses the same views eagerly.
  SSR hydration preloads use low fetch priority so CSS and fonts load first.
  Lazy chunks use `as=fetch` hints matching Shadow's XHR loader.
- `npm run dev`: watch SCSS and PostCSS outputs for local development.
- `bb audit <label>`: measure isolated production bundles
  and check optional dependency ownership without overwriting watched output.
  It also writes a source-map-based `report.html`/`report.json` identifying each
  source's actual optimized contribution.
- `npm run build`: produce shared CSS, a development-only inspector sheet, and
  independent feature sheets. Module
  specs declare literal `:styles`; CSS starts alongside JS acquisition. SSR emits
  route styles in the initial head, inlining small feature sheets within a bounded
  budget; Optimus fingerprints each bundle separately.
  See `doc/styles.md` for source ownership and loading behavior.
- `bb images:responsive`: rebuild the opt-in image catalog's sized AVIF/WebP assets.
- `bb videos:responsive`: rebuild the opt-in video catalog's mobile renditions.
- `bb fonts:icons`: regenerate small application icon fonts and full-font Unicode
  fallbacks with optional Python authoring tools; see `doc/styles.md`.
- `npm run init`: bootstrap CSS output, locked local npm tools, and the vendored SDK.
- `make hooks`: install the tracked pre-commit hook; staged public JPG/PNG images
  generate staged WebP/AVIF variants without including unstaged source edits.
- Live re-frame debugging: read the installed `re-frame-pair` skill, then run `bb pair discover-app` before inspecting the runtime. See `doc/re-frame-pair.md`; the wrapper selects `:app-dev` and Lein's nREPL port.

## Dependency ownership

- Shadow owns the CLJS/Closure compiler graph in `:provided`; explicit production build commands include that profile, while runtime packaging excludes it. Keep the managed Codox analyzer version aligned with Shadow.
- Use locked local npm tools and run `npm run vendor:sync` after Supabase SDK updates.
- 10x/re-frisk are opt-in through `:legacy-debug`; default development retains the custom console and re-frame-pair tracing. See `doc/re-frame-pair.md`.
- Release builds reject development inspectors in their resolved source graph.
  Periodically remove completed isolated `.shadow-cljs/builds/audit-*` caches;
  inspect active workers first and retain watched caches and useful audit reports.
- The CIDER Lein plugin supplies CIDER middleware; list Piggieback and Shadow explicitly in the development REPL handler. See `doc/dependencies.md` for upstream startup warnings.
- Keep Ring mocks in `:project/test`, prototype dependencies/source paths in `:experiments`, and the S3 wagon in `:s3-publish`.
- The personal `deploy-private` alias uses `lein-shell` and AWS CLI with a seeded local Maven staging repository; see `doc/dependencies.md` and `doc/lein-profiles.clj`.
- Review resolved Maven conflicts and both npm audit scopes on dependency upgrades. See `doc/dependencies.md`.

## Docker dependency updates
- The prefab builder includes `node_modules` and Maven artifacts; reuse it for normal source changes.
- When changing `package.json`, `package-lock.json`, `project.clj` dependencies, or `Dockerfile.builder`, run and verify `make docker-prefab` to publish the refreshed dependency-hash image and the staging compatibility tag.
- Keep the fallback `prefab` stage in `Dockerfile` aligned with `Dockerfile.builder`. Do not refresh the prefab for unrelated application-source edits.
- `Dockerfile`'s independent `media-tools` runtime stage installs and smoke-tests
  upload codecs; `docker build --target media-tools .` verifies it without a prefab refresh.
- See `doc/docker-builds.md` for registry setup and deployment recovery.

## Coding Style & Naming Conventions
- Clojure/ClojureScript: follow standard idioms (2-space indentation, align threading macros), use kebab-case for vars/functions, and keep namespaces aligned with file paths.
- Maps: keep all entries on one line only when the whole map fits comfortably, separating entries with commas. Otherwise put each key/value entry on its own line, aligning keys; never pack several entries onto a line of a multiline map. Apply this to new and changed code while preserving comments.
- Re-frame: do not use ns-scoped keywords, but rather simple ns based on module name.
- CLJS: features live under `modules/`, with events.cljs, subs.cljs, views.cljs and module.cljs as needed. Keep their portable declarations beside the implementation.
- SCSS: keep files modular in `resources/scss`; prefer BEM-ish class names when adding new components.
  Use relative units (`rem`, `em`, percentages or viewport units), never `px`,
  for new or changed styling, sizing, spacing and media queries.
- Avoid introducing new formatters unless the team agrees; none are enforced in-repo.
- Always confirm that variables (symbols) that are referred to actually exist in the given namespace, do not assume anything just from implicit context.

## Frontend Architecture: React, Reagent, Re-frame, and Shadow
These are required principles for new code and changes to existing code. Follow the frameworks' lifecycle and data-flow models, and use the application's existing abstractions before introducing another mechanism.

### Data sourcing and state
- Read application state through re-frame subscriptions, using the existing subscription helpers and `tolgraven.react` shim. Change state through registered events and the existing update helpers. Do not dereference or mutate `re-frame.db/app-db` directly from views, routing code, or page preparation helpers.
- Keep subscription computations and event-db handlers pure. Put HTTP, Supabase, Strapi, localStorage, timers, and other external side effects in registered effects or dedicated lifecycle adapters. Pass results back through events that update app-db; components then observe them through subscriptions.
- Use the existing subscription-backed Supabase and Strapi bindings. A subscription may acquire an existing managed data source; the source adapter owns the read, deduplication, result events, and cleanup. Do not perform network reads inside a subscription's derived-value computation or component render.
- Declare content dependencies in component, module, or page specifications. Preloading must acquire the same managed subscriptions/data bindings that the rendered component uses. Do not add a parallel imperative preparation namespace, duplicate query definitions, or fetch content directly from a page/view to avoid using the normal data flow.
- Batch and deduplicate pending data loads and persistence operations through the shared queues. Distinguish pending, successfully loaded empty, failed, and cached data. Preserve usable cached content during refresh and report real failures through the established notification/retry system.
- Release subscriptions, watches, listeners, and live connections when their owning lifecycle ends. Retain app-db content caches for reuse; temporary preloading must not dispose a subscription still owned by a mounted component.
- Use the scoped subscription/update helpers for persistent component, module, page, and shared state. Reserve local Reagent state for transient component-owned state; do not introduce a second application store or bypass event-driven persistence.
  Scoped helpers return native re-frame subscriptions; write with `>reset`/`>update`.
  Persistence runs through registered effects after accepted event transactions.

### Components and DOM ownership
- Build UI declaratively with Reagent/React components, preferably the existing composable `defc` capabilities. Put feature specifications in the declaration, use `<component>` naming, and avoid extra wrappers or class-component lifecycle machinery when a function component suffices.
- Keep render functions pure. Schedule effects, subscriptions, observers, and cleanup through the established lifecycle abstractions. Use stable React keys and follow hook ordering and ownership rules.
- Let React own rendered DOM. Do not use `innerHTML`, `appendChild`, manual node replacement/removal, or imperative class/style/text changes to implement UI state, loading, errors, navigation, or transitions. Render fallback/error/loading components and derive attributes from state instead.
- Necessary browser interop, such as measurement, focus, scrolling, and observers, belongs in a small ref/effect adapter with cleanup and server-environment guards. DOM measurement is not permission to mutate React-owned markup. Keep direct DOM setup in browser tests separate from production UI code.
- Use the shared component appearance, visibility, and deferred-unmount capabilities. Events express intent (for example, closing a panel); the component lifecycle owns its exit animation and eventual unmount. Do not duplicate animation timers in business events.

### SSR, hydration, and navigation
- Render the same components for SSR and the browser. Keep server-specific data acquisition and browser interop at adapter boundaries; do not maintain alternate copies of page/component markup for SSR.
- Restore the initial public content/state before hydration so the first client render matches the server HTML. Hydration, restored content, and already-visible content must not pass through loading placeholders or replay entry animations. Preserve existing visuals and behavior unless a UI change was explicitly requested.
- After the SPA starts, internal navigation uses the router, module loader, and regular subscription-backed data bindings. Do not request SSR pages or replace server HTML during SPA navigation. Declare page capabilities and dependencies in module-local `pages.cljc` specs. Each spec is a native Reitit route tree and may contain multiple or nested pages; compose these trees into routers without a global page inventory. Page declaration namespaces must not require their module views, events, or subscriptions.

### ClojureScript and JavaScript interop
- Prefer Clojure maps, vectors, destructuring and sequence functions within application code. Do not recursively convert Reagent props that are already Clojure values, or inspect React element internals to recover data.
- Convert JSON/SDK payloads once at the boundary using standard `js->clj`/`clj->js` options. Native events, DOM measurements, React refs and SDK objects legitimately require property access; keep that access in the adapter that owns it.
- Keep native Promises where a JavaScript API or shared asynchronous adapter requires them. Page/component code should declare dependencies and dispatch events rather than duplicate transport queries and Promise chains. Do not introduce an async library or thin wrappers solely to hide `js/Promise` syntax.
- Keep adapted React component identities stable across renders. Use Clojure props with Reagent adapters; reserve `#js` containers for APIs that actually require JavaScript values.

### Toolchain and verification
- Use the existing `tolgraven.react` shim so development uses the instrumented re-frame API and non-debug builds use the regular API. Do not bypass the shim or introduce a second React/Reagent runtime.
- Use the configured Shadow CLJS builds and module boundaries. Keep browser-only APIs out of server execution, and verify referenced framework symbols against the installed versions rather than assuming an API exists.
- Inspect the running Shadow worker and its compile errors/warnings after changes. Do not launch a competing compile for a build already being watched; use its existing worker/nREPL. Compile affected browser, test, and SSR targets as appropriate.
- `:app` and `:app-dev` share output. Isolate production build output during a dev watch, or pause the watch and restore its assets before browser checks.
- Verify behavior in the browser, not just successful compilation: cold direct loads, hydration, SPA navigation in both directions, cached/history return, and relevant empty/error/recovery states. For hydration or transition changes, check for spinners, flashes, layout jumps, missing content, and console errors. Never report a check as passed without actually running it.

### Code symbol naming
All of the below are targets, not facts, so do not rely on them as hard rules, but
rather as guidelines to help make code more readable and maintainable. If you have a good reason to deviate, do so, but be sure to explain the rationale in comments or PR descriptions. Generally use these for new code, and feel free to update existing code towards this style when you have the chance, but do not feel obligated to refactor large amounts of existing code just to fit these guidelines.
- Use a star * *prefix for derefable @*symbols (atoms, refs, vars etc). Example:
  - `(defonce *app-state (atom {}))`
  - `(defn get-value [] @*app-state)`
- Use a ! suffix! for functions with side effects. Example:
  - `(defn save-data! [data] ...)`
- Use a ! !prefix for frontend functions causing backend side effects (e.g., dispatching events that trigger HTTP calls). Example:
  - `(defn !fetch-user-data [user-id] ...)`
- Use a ? suffix? for predicate functions returning boolean values, and boolean value vars. Example:
  - `(defn valid-input? [input] ...)`
  - `(def user-logged-in? true)`
- Use a - suffix for keyword variables, to indicate they hold keyword values. Example:
  - `(def current-page- :home)`
- Use <> around Reagent component functions (that will go inside a vector). Example:
  - `(defn <user-profile> [] ...)`
  - `[<user-profile>]`

## Testing Guidelines
- Use `lein with-profile +project/test test` for backend tests; browser tests use Shadow `:app-test` and the runner in `bb test:browser`.
- Generate hydration fixtures from the current Node renderer with `bb ssr:fixtures` before browser tests. The obsolete Doo runner has been removed.
- Inspect existing watched builds before compiling. Live development normally watches `:app-dev`, `:ssr`, and `:return-worker`; never overwrite watched output with another compiler.
- See `doc/testing.md` for mounted subscription workflows, live integration boundaries and browser checks. Unit replacements do not establish live service behavior.

## Commit & Pull Request Guidelines
- Commit each completed, verified task before starting the next. Subagents commit their own finished changes; coordinate the shared index so unrelated work is never included.
- Commit messages follow `scope: summary` (examples in git history: `scss: fix theme var helper broken`). Can also use `scope: subscope: summary`. Keep summaries short and imperative.
- PRs should include: a clear description, related issue links, and screenshots/gifs for UI changes.
- Note any config changes (e.g., `env/*` or Supabase schema) in the PR description.

## Configuration & Secrets
- Local config lives in `config/local.dev.edn` and `config/local.test.edn`; production config is under `env/prod/resources`.
- Do not commit secrets; prefer env vars or injected config files.

## Deploy
- Follow `doc/docker-builds.md` for `make docker` and PR preview cleanup; `doc/site-provisioning.md` covers separate sites and production/staging services.
