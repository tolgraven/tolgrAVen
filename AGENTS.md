# Repository Guidelines

## Project Structure & Module Organization
- `src/clj`, `src/cljs`, `src/cljc`: Clojure, ClojureScript, and shared code.
- `resources/`: runtime assets and public output; SCSS lives in `resources/scss` and builds into `resources/public/css/tolgraven`.
- `test/clj`, `test/cljs`: backend and frontend tests.
- `env/`: environment-specific source/resources (dev/test/prod).
- `functions/`: Firebase Cloud Functions (Node/ESLint).
- `scripts/`: media conversion helpers (images/videos).
- `doc/`: project documentation.

## Build, Test, and Development Commands
- `lein repl`: start the HTTP server and Shadow CLJS REPL (see `README.md`).
- `npm run dev`: watch SCSS and PostCSS outputs for local development.
- `npm run build`: produce compressed CSS assets for production.
- `npm run init`: bootstrap CSS output dir and global tool installs.
- `npm --prefix functions run lint`: lint Firebase functions with ESLint.
- `npm --prefix functions run serve`: run functions emulator locally.

## Coding Style & Naming Conventions
- Clojure/ClojureScript: follow standard idioms (2-space indentation, align threading macros), use kebab-case for vars/functions, and keep namespaces aligned with file paths.
- Re-frame: do not use ns-scoped keywords, but rather simple ns based on module name.
- CLJS: general structure (apart from top-level) is folder containing module with events.cljs, subs.cljs, views.cljs, module.cljs with spec.
- SCSS: keep files modular in `resources/scss`; prefer BEM-ish class names when adding new components.
- Avoid introducing new formatters unless the team agrees; none are enforced in-repo.
- Always confirm that variables (symbols) that are referred to actually exist in the given namespace, do not assume anything just from implicit context.

### Code symbol naming
- Use a star *prefix for derefable @symbols (atoms, refs, vars etc). Example:
  - `(defonce *app-state (atom {}))`
  - `(defn get-value [] @*app-state)`
- Use a !suffix for functions with side effects. Example:
  - `(defn save-data! [data] ...)`
- Use a !prefix for frontend functions causing backend side effects (e.g., dispatching events that trigger HTTP calls). Example:
  - `(defn !fetch-user-data [user-id] ...)`
- Use a ? suffix? for predicate functions returning boolean values, and boolean value vars. Example:
  - `(defn valid-input? [input] ...)`
  - `(def user-logged-in? true)`
- Use a - suffix for keyword variables, to indicate they hold keyword values. Example:
  - `(def current-page- :home)`
- Use <> around Reagent component functions (that will go inside a vector). Example:
  - `(defn <user-profile> [] ...)

## Testing Guidelines
- Clojure tests use `clojure.test` in `test/clj`; run with `lein test`.
- ClojureScript tests live in `test/cljs` and are wired via a `doo` test build in `project.clj`. Use your preferred `doo` runner if needed.
- Name tests `*_test.clj` / `*_test.cljs` (see `test/clj/tolgraven/handler_test.clj`).
- Tests are not yet a priority and regular dev process is rather by confirming compiles go through and lints are ok. Try to use LSP MCP instead if available, and connect to nREPL + eval `(shadow/select-repl :app-dev)`

## Commit & Pull Request Guidelines
- Commit messages follow `scope: summary` (examples in git history: `scss: fix theme var helper broken`). Can also use `scope: subscope: summary`. Keep summaries short and imperative.
- PRs should include: a clear description, related issue links, and screenshots/gifs for UI changes.
- Note any config changes (e.g., `env/*` or Firebase rules) in the PR description.

## Configuration & Secrets
- Local config lives in `dev-config.edn` and `test-config.edn`; production config is under `env/prod/resources`.
- Do not commit secrets; prefer env vars or injected config files.

## Deploy
- Works using 
