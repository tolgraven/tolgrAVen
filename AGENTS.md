# Repository Guidelines

## Project Structure & Module Organization
- `src/clj`, `src/cljs`, `src/cljc`: Clojure, ClojureScript, and shared code.
- `resources/`: runtime assets and public output; SCSS lives in `resources/scss` and builds into `resources/public/css/tolgraven`.
- `test/clj`, `test/cljs`: backend and frontend tests.
- `env/`: environment-specific source/resources (dev/test/prod).
- `resources/supabase/`: schema, native operations and account import SQL.
- `scripts/`: media conversion helpers (images/videos).
- `doc/`: project documentation.

## Build, Test, and Development Commands
- `lein repl`: start the HTTP server and Shadow CLJS REPL (see `README.md`).
- `npm run dev`: watch SCSS and PostCSS outputs for local development.
- `npm run build`: produce compressed CSS assets for production.
- `npm run init`: bootstrap CSS output dir and global tool installs.
- Live re-frame debugging: read the installed `re-frame-pair` skill, then run `bash scripts/re-frame-pair.sh discover-app` before inspecting the runtime. See `doc/re-frame-pair.md`; the wrapper selects `:app-dev` and Lein's nREPL port.

## Docker dependency updates
- The prefab builder includes `node_modules` and Maven artifacts; reuse it for normal source changes.
- When changing `package.json`, `package-lock.json`, `project.clj` dependencies, or `Dockerfile.builder`, run and verify `make docker-prefab` to publish the refreshed dependency-hash image and the staging compatibility tag.
- Keep the fallback `prefab` stage in `Dockerfile` aligned with `Dockerfile.builder`. Do not refresh the prefab for unrelated application-source edits.
- See `doc/docker-builds.md` for registry setup and deployment recovery.

## Coding Style & Naming Conventions
- Clojure/ClojureScript: follow standard idioms (2-space indentation, align threading macros), use kebab-case for vars/functions, and keep namespaces aligned with file paths.
- Re-frame: do not use ns-scoped keywords, but rather simple ns based on module name.
- CLJS: general structure (apart from top-level) is folder containing module with events.cljs, subs.cljs, views.cljs, module.cljs with spec.
- SCSS: keep files modular in `resources/scss`; prefer BEM-ish class names when adding new components.
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
- Clojure tests use `clojure.test` in `test/clj`; run with `lein test`.
- ClojureScript tests live in `test/cljs` and are wired via a `doo` test build in `project.clj`. Use your preferred `doo` runner if needed.
- Name tests `*_test.clj` / `*_test.cljs` (see `test/clj/tolgraven/handler_test.clj`).
- Tests are not yet a priority and regular dev process is rather by confirming compiles go through and lints are ok. Try to use LSP MCP instead if available, and connect to nREPL + eval `(shadow/select-repl :app-dev)`

## Commit & Pull Request Guidelines
- Commit messages follow `scope: summary` (examples in git history: `scss: fix theme var helper broken`). Can also use `scope: subscope: summary`. Keep summaries short and imperative.
- PRs should include: a clear description, related issue links, and screenshots/gifs for UI changes.
- Note any config changes (e.g., `env/*` or Supabase schema) in the PR description.

## Configuration & Secrets
- Local config lives in `dev-config.edn` and `test-config.edn`; production config is under `env/prod/resources`.
- Do not commit secrets; prefer env vars or injected config files.

## Deploy
- Works using 
