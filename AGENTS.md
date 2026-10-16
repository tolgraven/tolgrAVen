# Repository Guidelines

Keep this file and every scoped `AGENTS.md` up to date in the same change that
alters the architecture, build workflow, or contracts they describe. Keep
instructions concise; detailed schemas, examples and user guides belong in `doc/`.
Do not retain completed investigation diaries or historical test counts as current guidance.

## Work completion and usage limits

Agents have no token allowance to manage and must not reason about or report
"tokens left." The user tracks their weekly usage cap. Continue the authorized
work through completion; do not shorten, defer or stop work because of an assumed
token budget.

## Required workflow for every request

Every request that changes this repository follows the complete workflow below,
including small fixes, documentation and `AGENTS.md` changes. A local edit,
commit, passing test run or unmerged PR is an intermediate step, not completion.
Use this standing authorization to commit, push, create/update the PR and merge
the task's changes in `tolgraven/tolgrAVen`; do not ask for the same authorization
again. Follow an explicit user instruction to stop earlier or change the workflow.
Read-only questions and investigations do not require artificial code changes or PRs.

Chrome can crash, and the user or another agent can close windows or tabs at any
time. Recover automatically: retry the existing installed Chrome connection,
rediscover page IDs, and reopen only a missing task page when necessary. If the app
is no longer running, reopen the installed Chrome and reconnect. Do not ask the
user to restore a routine connection or reuse stale page IDs. Never launch a
separate browser distribution or clone as a recovery shortcut; preserve other
sessions' tabs and restore any borrowed tab when finished.

1. **Use a managed worktree and feature branch.** Inspect the current checkout,
   worktrees and remote state first. Start from current `origin/master` on a
   task-specific `codex/<task>` branch in
   `/Users/tol/.codex/worktrees/<task>/tolgrAVen`, the default worktree location.
   Continue the same task in its existing worktree. Never implement on `master`
   or put task worktrees in `/tmp` or `/private/tmp`. If sandbox access is needed,
   request access to the default location instead of silently relocating it.
   Preserve other sessions' changes, worktrees, REPLs and watched build output.
2. **Inspect and implement.** Read applicable guidance and surrounding source;
   establish the cause of a reported issue and follow existing project patterns.
   Finish the requested behavior and commit coherent, verified milestones.
3. **Test and inspect hands-on.** Run focused checks and required builds. Exercise
   the actual affected flow in the existing browser or app, inspect rendered UI,
   console/network behavior and relevant failure paths. For frontend changes,
   check cold loading, hydration, SPA navigation and history returns as relevant;
   inspect narrow and wide layouts when appearance changes. Compilation or unit
   tests alone do not establish working UI. Report any genuinely blocked check.
4. **Publish a draft PR.** Use `bb pr` instead of raw `gh pr` to create or edit
   PRs; it retains the owning session and appends contributing session IDs. Install
   tracked hooks with `bb hooks` so commits carry `Codex-Session` trailers. Pass
   `--contributor UUID` for contributing sessions whose work has no commit trailer.
   See `doc/tooling.md`. Push the feature branch and open a draft PR against
   `master` once a coherent committed checkpoint exists. Keep its head and
   description current, with concrete behavior, verification evidence, relevant
   screenshots and remaining limits. Do not stop at a local-only handoff.
5. **Review the PR closely and fix findings.** Use the
   [Code Review plugin](plugin://code-review@openai-bundled/) on the complete
   current PR diff and surrounding code. Check correctness, regressions,
   security, project conventions, preservation and verification gaps. Resolve
   actionable findings, rerun affected checks and review the resulting changes.
   Passing CI or a security scan does not replace this review. If review tooling
   is unavailable, explain the blocker; do not silently skip the required review.
6. **Merge and verify integration.** Once the reviewed head is pushed, required
   verification and CI pass, and the description matches the final change, mark
   the PR ready and squash-merge it. Reconcile upstream changes and resolve
   conflicts manually, file by file; recheck any changed behavior before merging.
   Confirm the remote merge, then synchronize local `master` without overwriting
   uncommitted work. Close only task-created tabs/servers and report the PR URL,
   merge commit, checks performed and material limits. Do not leave an otherwise
   completed request unmerged or ask whether to finish these routine steps.

## Task resource ownership and cleanup

- Track task-created browser tabs/windows, servers, build snapshots and worktree
  paths while working. Reuse one existing task tab for sequential checks. Create
  an additional private context only when the check requires isolation, and close
  it as soon as that check finishes. Close every completed task-created tab/window
  promptly, including older test runners; never quit Chrome or close user-owned
  tabs or another session's still-needed checks. Relay this rule to active subagents.
  Close task pages before waiting for builds, reviews or provider availability;
  reopen one only when browser checks resume. Do not retain idle test windows.
- Cleanup is part of completion, not an optional later pass. After merge and local
  integration, stop only owned verification servers, remove finished isolated output
  snapshots, and retire the task worktree and its compiler caches. Preserve active
  watches/fixtures, uncommitted or unpublished work, ignored private data and useful
  verification records; archive the latter before removing the worktree.
- From another checkout, run `bb cleanup --worktree <task-path> --remove-worktree`
  to inspect the plan, then add `--apply`. The command requires a clean registered
  feature worktree, an exact merged PR head present in `origin/master`, no open
  follow-up PR and no processes, build reservations or container mounts using its
  directory (including cache-disabled and older builds). It archives ignored task data
  under the primary checkout's `.local-wip/task-archives`, preserves branch refs,
  and uses ordinary `git worktree remove` without force. Detached or ambiguous
  worktrees require manual investigation; age or ahead/behind counts are not proof.
- Agent compiler caches live in the primary checkout's ignored
  `.local-wip/shadow-caches`, shared across its worktrees and separate from watched
  `.shadow-cljs/builds`. Run `bb cleanup` to inspect and `bb cleanup --apply` to prune
  unlocked obsolete/expired/LRU entries. Never delete lock files, active writer
  caches, watched caches or another session's unfinished outputs. See
  `doc/resource-controls.md` for legacy cache cleanup and the 2 GiB repository budget.

## Current architecture
- Strapi site settings own published page layouts. `website.page-layout` owns the
  bounded portable contract/defaults; module default views and Home's native
  sections export the component picker. Home's generic renderer retains managed
  data/module boundaries; native routes are generated from published layout paths.
  See `doc/page-layouts.md` for publishing, identity and responsive grid behavior.
- Administrator inline CMS editing uses `:content/view` bindings, which retain
  native values and attach field paths/mode as metadata. The lazy `content-editor`
  owns account-bound drafts and effects; server writes verify current administrator
  roles and use a separate private CMS write token. Configuration and section
  ordering stay in Strapi admin. See `doc/strapi-content.md`.
- CV regular boxes open measured, viewport-centered detail surfaces. Timeline
  containers are a separate kind with bounded child timelines and a regular
  starter entry; nested writes verify their parent identities and entry count.
  Keep motion measurement/focus in the owned portal adapter and content writes
  in the editor's re-frame events/effects.
- Keep the full-featured `defc` based on upstream `reagent.core/defc`. Hooks delegate
  to upstream `reagent.hooks` through the existing shim; dependency collections
  use Clojure equality and equal state updates retain identity. Adapter review
  and ownership instructions live in `features/reagent-native-refactor.md`.
  See `doc/reagent-2-react-19-guide.md` for the library comparison and design rationale.
- Reagent 2 / React 19 components use `defc`; spec-capable instances accept map
  specs or feature-vector shorthand. Full pages use `defpage` and always have an error boundary. Application React, Reagent and re-frame calls go through `website.react` as `r`; raw framework imports belong to its runtime boundary.
- Module-local `pages.cljc` files contain native Reitit route trees. Module specs expose these declarations; browser and server routers compose them.
- Search sources belong to module `:server :search` declarations; private SQL
  projections and leased delivery feed server-owned Typesense. Lightweight preview
  modules require both Search and their content owner through `:site/requires`.
  Preserve native component destinations and safe text/mark fragments. See `doc/search.md`.
- JVM adapters acquire public data with snapshot-owned, bounded Supabase REST
  connections; the eager Shadow `:ssr` Node build renders the same page components as the browser. `:ssr` in config controls streaming and worker settings.
- Module references activate near the viewport by default; hidden panels use
  explicit intent. Completed SSR module views retain their DOM until selective
  hydration; module CSS is available for that static view.
- Cold/expired SSR streams a component-derived skeleton then the completed page.
  Fresh cached SSR skips the skeleton (`:ssr :cache-ttl-ms`, default one hour).
  Completed network SSR starts the original document entrance after hero decoding;
  hydration preserves its DOM and animation clock. Local/history returns stay settled.
- Analytics initialization and script acquisition belong to the browser lifecycle
  after hydration and page readiness. The deferred host's timeout replaces hidden
  markup with script Hiccup; public settings load through the shared dependency
  adapter. The ID lives in server config; SSR emits no analytics markup/bootstrap.
- Readable link previews belong to content containers, acquire text/image data only
  on activation, and delay optional sandboxed miniatures until the card paints.
  Frame policy controls embedding; menu navigation does not opt into previews.
- After hydration, navigation is entirely SPA: commit the destination immediately, then acquire code/data through shared bindings. Related blog routes retain their shell.
- `boot.cljs` owns ordered document listeners, committed UI setup and deferred
  background hosts. Register browser listeners through effects with an owner and
  cleanup; importing a namespace must not install document listeners.
- Shared local-return view state is opt-in through shell/navigation/module owner
  declarations; scroll measurements, header visibility, timers and boot flags are
  rebuilt for each document.
- The standalone `:document` CLJS target supplies parser-blocking head return policy
  and body SSR entrance/image readiness before hydration; build it for deployment.
- Local returns and reloads may use a service-worker document pairing rendered HTML with exact EDN state/content; install that state before hydration. The `:return-worker` build is part of the deployment.
- Read `src/frontend/website/AGENTS.md`, `src/cljc/website/AGENTS.md`, `src/backend/website/AGENTS.md`, and `test/AGENTS.md` when touching those areas.
- Markdown code, including untagged blocks and inline snippets, shares the deferred
  formatter boundary. Node SSR renders Bruvbox-highlighted tokens immediately;
  browser acquisition waits for the existing after-page gate. The blog supplies
  its Clojure fallback and detection set; reusable Markdown has no site-specific default.
  Bruvbox palette/token ownership lives in `modules/highlight/theme.cljs`.

## Project Structure & Module Organization
- Application namespaces use `website.*` across CLJ, CLJC and CLJS. Standalone
  pre-hydration `bootstrap.*` sources live beside `website/` in `src/frontend/`.
  Keep external asset URLs and existing persisted storage identities stable.
- `src/backend`, `src/frontend`, `src/cljc`: Backend code, frontend code, and shared CLJC infrastructure. Feature implementations and their portable declarations live in `src/frontend/website/modules/<module>/`.
- `components/ui/` groups shared UI by component type. Import each component's
  owning namespace directly; do not retain forwarding definitions or compatibility exports.
- Feature loading is explicit through module declarations or `modules/<feature>/interface`.
  Shared components stay lightweight; preserve substantive prototypes when moving their owner.
- Markdown declares its node/component mapping in `modules/markdown/views.cljs`.
  Highlight owns the shared code-block controls and the separately deferred token formatter.
- `src/frontend/website/components/`: reusable rendered UI and site shell;
  `component/` contains the declaration/lifecycle runtime.
- `experiments/clj`: preserved JVM prototypes, compiled only with `:experiments`.
- `resources/`: runtime assets and public output; SCSS lives in `resources/scss` and builds into `resources/public/css/website`.
- `test/clj`, `test/cljs`: backend and frontend tests.
- `env/`: environment-specific source/resources (dev/test/prod).
- `resources/supabase/`: schema, native operations and account import SQL.
- CMS provisioning creates team-bound deploy-only tokens for private Strapi admin actions; preserve server-only credential wiring and transactional retries.
- `scripts/`: grouped Babashka build/media/dev/test tasks and explicit operations adapters; `bb.edn` is the task catalog. See `doc/tooling.md`.
- `config/`: private local overrides and tracked examples; environment classpath defaults remain in `env/`.
- `doc/`: project documentation.

## Shared schemas
- `schema/dsl.cljc` expands `:?key` optional map entries and nullable `:?type` /
  `[:?type ...]` forms before Malli interpretation. Keep schemas as data; validate
  through the existing adapters or call `dsl/->malli` before direct Malli APIs.
  See `doc/schemas.md` for native syntax and literal-value rules.
- Browser schema declarations stay eager data; compose with native `[:merge ...]`
  expressions. The `:coercion` module owns Malli interpretation and the custom
  registry. Network SSR supplies typed route parameters; boot acquires the engine
  after hydration/readiness, while early SPA navigation waits before controllers.
  Client/local restoration validates disk input before use. See `doc/schemas.md`.
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
- Agent Java/Lein builds use `tolgraven-cpu run -- <command>` (two shared build slots, isolated
  Docker prefab, 1.5 CPUs/4 GiB per slot; 3 Docker CPUs combined). Dockerfile/prefab work uses
  `tolgraven-cpu run --mode buildkit -- <command>` so BuildKit steps also have a quota.
  Increase a documented heap/memory limit only when required; avoid competing watched output.
  Fresh output snapshots reuse a separately locked worktree-scoped Shadow compiler disk
  cache; never mount or overwrite a live checkout’s watched cache. See `doc/resource-controls.md`.
  `--mode host` uses nice 10 and the shared build slot when live watches/interop
  require macOS. The installed monitor caps exact app renderers and only explicitly
  registered agent host Java families; user-launched Lein clients and their
  backend/Shadow descendants remain unthrottled. Never issue ad
  hoc STOP/CONT or global process kills. See
  `doc/resource-controls.md` for scope, telemetry, recovery and explicit overrides.
- `lein with-profile prod run` overlays `config/prod-local.edn` for local HTTP on
  loopback and local SSR/converter paths; packaged production keeps secure proxy
  defaults. `bb prod:build` releases separate `prod-local-*` Shadow targets into
  `target/production`; local prod loads those resources before shared assets and
  preflights the release browser manifest and both document bootstrap scripts
  before initialization logging/asset loading. See `doc/testing.md`.
- `lein repl`: check/rebuild stale CSS before opening HTTP and the Clojure REPL;
  current SSR/browser markup must not paint against sheets from an older checkout.
  Deferred Shadow watches compile
  sequentially without blocking REPL acknowledgement. The standalone `:document`
  target releases first (no HMR); document/bootstrap requests await only that
  bounded release barrier, while unrelated assets/API remain available. `(cljs-repl)` waits for
  startup and selects the browser runtime in the calling session. See `README.md`.
- Browser builds discover `modules/*/module.cljs` through `website.build.browser/process`;
  development browser builds also select `env/dev/cljs/website/modules/` with the
  `:dev` reader feature. Production and Node SSR exclude those declarations.
  a missing spec `:id` uses the parent namespace of `.module`. Spec
  `:bundle/depends-on` declares hard Shadow prerequisites (default `#{:main}`);
  optional features use explicit lightweight module interfaces and acquire code
  through the shared loader only when needed. Keep build declarations in `spec`,
  never namespace metadata. Runtime inventories track declaration resources
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
- `npm run build`: produce shared CSS, a development-only inspector sheet, a
  compressed icon selector/font-face sheet, and independent feature sheets.
  `bb site:resolve` snapshots CMS variable CSS beside capability selections.
  Parse it as plain custom-property CSS before output writes and append it only
  after the shell defaults; keep it in style freshness/watch inputs and out of
  tracked SCSS, feature sheets and runtime source packaging. See `doc/styles.md`.
  Module
  specs infer an existing `/css/website/modules/<id>.min.css` when `:styles`
  is absent; matching SCSS establishes ownership before generated output exists.
  Explicit `:styles` replaces that default, including `[]` to opt out.
  CSS starts alongside JS acquisition. SSR emits
  route styles in the initial head, inlining small feature sheets within a bounded
  budget. Popup-only modules may declare `:ssr-styles :deferred`; Optimus
  fingerprints each bundle separately. Production packaging prepares its inventory
  once; runtime imports the validated artifact without minification or image conversion.
  Literal component/module `:icons` export native font-glyph rules into the same
  style inventory; small font faces live in the shell. `bb fonts:icons` also retains
  committed SVG exports. Preserve original glyph metrics and rasterization.
  Text faces stay consumer-scoped; `defc`'s `:font` feature owns controlled late
  upgrades independently of stylesheet/module readiness.
  Production asset preparation prunes unused selectors, custom properties,
  keyframes and font faces against released code before Optimus fingerprints CSS;
  variable dependencies span all selected lazy sheets. Development output stays
  complete. See `doc/styles.md` for source ownership and loading behavior.
- `bb images:responsive [--force]`: discover still raster originals with longest edge ≥960, native width >320 and source size ≥64 KiB and rebuild the responsive catalog's sized AVIF/WebP assets.
- `bb videos:responsive`: rebuild the opt-in video catalog's mobile renditions.
- `bb fonts:icons`: regenerate small application icon fonts and full-font Unicode
  fallbacks with optional Python authoring tools; see `doc/styles.md`.
- `npm run init`: bootstrap CSS output, locked local npm tools, and the vendored SDK.
- `make hooks`: install the tracked pre-commit hook; staged public JPG/PNG images
  generate staged WebP/AVIF variants without including unstaged source edits.
- Live re-frame debugging: read the installed `re-frame-pair` skill, then run `bb pair discover-app` before inspecting the runtime. See `doc/re-frame-pair.md`; the wrapper selects `:app-dev` and Lein's nREPL port.

The CMS provisioning manifest also carries the actual `_vars.scss` as a read-only,
searchable variable reference; refresh it when provisioning the editor. Do not
maintain a duplicate handwritten variable catalog.

## Dependency ownership

- Timbre owns JVM logging configuration in `website.log`; tools.logging and
  SLF4J route through its existing adapters. Select JBoss's SLF4J provider in
  Lein and direct Java launches before Undertow loads. Use the shared
  `org.clj-commons/pretty`, not the duplicate `io.aviso/pretty` or Logback XML.
  See `doc/dependencies.md`.

- Shadow owns the CLJS/Closure compiler graph in `:provided`; explicit production build commands include that profile, while runtime packaging excludes it. Keep the managed Codox analyzer version aligned with Shadow.
- Use locked local npm tools. Supabase uses its npm SDK inside the lazy `:supabase` module;
  `npm run vendor:sync` maintains only the preserved legacy UMD reference.
- The editor configure hook generates a version/hash-checked selective public SDK
  entry in ignored `target/generated-sdk`. All editor browser/test/SSR builds use
  the same resolver; production audits reject unused CodeMirror/Lezer/diff-source.
  Keep original SDK modules and shared React/Gurx ownership. See `doc/markdown-authoring.md`.
- 10x/re-frisk are opt-in through `:legacy-debug`; default development retains the custom console and re-frame-pair tracing. See `doc/re-frame-pair.md`.
- Release builds reject development inspectors in their resolved source graph.
  Periodically remove completed isolated `.shadow-cljs/builds/audit-*` caches;
  inspect active workers first and retain watched caches and useful audit reports.
- The CIDER Lein plugin supplies CIDER middleware; list Piggieback and Shadow
  explicitly in the development REPL handler, and
  Piggieback in Shadow's separate nREPL configuration. Keep the cold-start timeout
  large enough for cold application initialization; watches run afterward.
  See `doc/dependencies.md`.
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
- In `let`, never redeclare a binding name for a transformed value. Use `original-name'`, then `original-name''` for successive stages, so each immutable binding is explicit. Apply this when updating existing bindings as well as writing new code.
- Maps: keep all entries on one line only when the whole map fits comfortably, separating entries with commas. Otherwise put each key/value entry on its own line, aligning keys; never pack several entries onto a line of a multiline map. Apply this to new and changed code while preserving comments.
- Re-frame: do not use ns-scoped keywords, but rather simple ns based on module name.
- CLJS: features live under `modules/`, with events.cljs, subs.cljs, views.cljs and module.cljs as needed. Keep their portable declarations beside the implementation.
- SCSS: keep files modular in `resources/scss`; prefer BEM-ish class names when adding new components.
  Use relative units (`rem`, `em`, percentages or viewport units), never `px`,
  for new or changed styling, sizing, spacing and media queries.
- Before submitting UI changes, triple-check the actual rendered appearance at
  narrow and wide widths: outer margins, inner padding on every edge, spacing
  between controls/text, alignment and overall balance. Inspect screenshots and
  correct cramped or uneven spacing; passing geometry tests is not sufficient.
- Avoid introducing new formatters unless the team agrees; none are enforced in-repo.
- Always confirm that variables (symbols) that are referred to actually exist in the given namespace, do not assume anything just from implicit context.

## Frontend Architecture: React, Reagent, Re-frame, and Shadow
These are required principles for new code and changes to existing code. Follow the frameworks' lifecycle and data-flow models, and use the application's existing abstractions before introducing another mechanism.

### Reagent and re-frame first

- Treat ordinary Reagent Hiccup and re-frame as the default implementation, including
  for performance work. Start with `defc`, declarative specifications, subscriptions,
  registered events and effects; reuse their existing lifecycle capabilities.
- Keep the flow inspectable: user intent -> event -> app-db update/effect -> result
  event -> subscription -> Hiccup. A reviewer should be able to trace that flow
  without reconstructing Promise chains, mirrored stores or hidden callbacks.
- Express DOM events with Reagent attributes (`:on-click`, `:on-pointer-down`,
  `:on-change`, etc.). Do not replace them with native listeners when Reagent can
  handle the interaction. Use Clojure props through stable Reagent adapters for
  third-party React components too.
- Prefer scoped `<sub`/`>reset`/`>update` state and layer-2/3 derived subscriptions
  over custom cursors, app-db watches or broad subscriptions with manual filtering.
  Compose subscribed inputs so unrelated updates do not recompute the view's data.
- Schedule delayed application events with `:dispatch-later` in event effects.
  Use existing loading, visibility, appearance and presence declarations for
  deferred work; do not build parallel timer/Promise orchestration in views.
- Modern React features (hooks, memoization, Suspense, transitions and portals)
  have a place when an existing abstraction cannot express the required lifecycle,
  interoperability or measured performance improvement. Explain the concrete need
  beside the adapter or in the PR; do not introduce them merely as an alternative
  style. A React mechanism must still respect declarative markup and re-frame's
  ownership of application state/effects.
- Keep justified browser adapters small, guarded for SSR and owned by a lifecycle
  with cleanup. A transient, high-frequency gesture may own refs and cancellable
  local timers; its resulting application action still dispatches an event. This
  exception does not justify putting network, persistence or business state in hooks.
- Read `doc/reagent-re-frame.md` for examples and the decision checklist, and the
  `src/frontend/website/AGENTS.md` for concrete adapter contracts. Review the entire diff against
  these principles before merging, including tests and failure paths.

### Data sourcing and state
- Read application state through re-frame subscriptions, using the existing subscription helpers and `website.react` shim. Change state through registered events and the existing update helpers. Do not dereference or mutate `re-frame.db/app-db` directly from views, routing code, or page preparation helpers.
- Keep subscription computations and event-db handlers pure. Put HTTP, Supabase, Strapi, localStorage, timers, and other external side effects in registered effects or dedicated lifecycle adapters. Pass results back through events that update app-db; components then observe them through subscriptions.
- Use the existing subscription-backed Supabase and Strapi bindings. A subscription may acquire an existing managed data source; the source adapter owns the read, deduplication, result events, and cleanup. Do not perform network reads inside a subscription's derived-value computation or component render.
- Declare content dependencies in component, module, or page specifications. Preloading must acquire the same managed subscriptions/data bindings that the rendered component uses. Do not add a parallel imperative preparation namespace, duplicate query definitions, or fetch content directly from a page/view to avoid using the normal data flow.
- Missing configuration for an enabled module must fail within that
  module and expose its local error/retry state; it must not gate unrelated
  modules or the shared shell. Startup loads only shared shell dependencies;
  route modules own their content.
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
- `website.react/use-effect` and `use-layout-effect` accept implicit nil for no
  cleanup. Hook dependencies are Clojure vectors/sequences compared by value;
  refs/functions/native handles remain opaque. Memo/callback helpers require
  explicit dependencies and preserve return values. Use these upstream Reagent
  delegates over raw React hook imports.
- Keep adapted React component identities stable across renders. Use Clojure props with Reagent adapters; reserve `#js` containers for APIs that actually require JavaScript values.

### Toolchain and verification
- Use the existing `website.react` shim so development uses the instrumented re-frame API and non-debug builds use the regular API. Do not bypass the shim or introduce a second React/Reagent runtime.
- Use the configured Shadow CLJS builds and module boundaries. Keep browser-only APIs out of server execution, and verify referenced framework symbols against the installed versions rather than assuming an API exists.
- Inspect the running Shadow worker and its compile errors/warnings after changes. Do not launch a competing compile for a build already being watched; use its existing worker/nREPL. Compile affected browser, test, and SSR targets as appropriate.
- `:app` and `:app-dev` share output. Isolate production build output during a dev watch, or pause the watch and restore its assets before browser checks.
- Verify behavior in the browser, not just successful compilation: cold direct loads, hydration, SPA navigation in both directions, cached/history return, and relevant empty/error/recovery states. For hydration or transition changes, check for spinners, flashes, layout jumps, missing content, and console errors. Never report a check as passed without actually running it.

- Development source documentation, on-page picking and profiling are described in
  `doc/dev-console.md`. Keep them behind build/environment boundaries; use normal
  Reagent/re-frame UI and the existing instrumentation adapters for observation.

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

- Clear viewport, network and CPU emulation after browser checks, including failure
  cleanup. Verify the reset on an adopted tab: another tool connection may have
  left a fixed viewport even when the reset reports success. User-facing tabs must
  follow the actual browser window again.
- Use `lein with-profile +project/test test` for backend tests; browser tests use Shadow `:app-test` and the runner in `bb test:browser`.
- Generate hydration fixtures from the current Node renderer with `bb ssr:fixtures` before browser tests. The obsolete Doo runner has been removed.
- Inspect existing watched builds before compiling. Live development normally watches `:app-dev`, `:ssr`, and `:return-worker`; never overwrite watched output with another compiler.
- See `doc/testing.md` for mounted subscription workflows, live integration boundaries and browser checks. Unit replacements do not establish live service behavior.

## Commit & Pull Request Guidelines
- Resolve merge and rebase conflicts manually, file by file, from the start.
  Inspect both sides and the intended combined behavior, edit each conflict
  explicitly, and review the resulting diff. Do not use bulk conflict-resolution
  scripts or blanket ours/theirs choices across files.
- Always open and keep in-progress PRs as drafts. Mark them ready for review only
  after implementation and required verification are complete, the current head is
  pushed, and the description matches it. Return a PR to draft when follow-up
  implementation starts.
- When assigned a PR, publish a draft once a coherent committed checkpoint exists;
  keep its head and description current while work continues. A local branch or
  a "ready for publication" handoff does not complete that assignment. Subagents
  must return the PR URL, verification status, and remaining work; the parent
  verifies publication and completes authorized review/merge before closing it.
- The required workflow above grants standing authorization for committing,
  pushing, creating/updating PRs and squash-merging the task's reviewed changes in
  `https://github.com/tolgraven/tolgrAVen`. It does not authorize force pushes,
  branch deletion, deployment, unrelated changes or publication elsewhere.
- GitHub Copilot code review credits are exhausted. Before every merge, perform a
  proper review with the [Code Review plugin](plugin://code-review@openai-bundled/).
  Review the complete current diff and relevant surrounding code for correctness,
  regressions, security, preservation of unfinished work, and verification gaps;
  resolve actionable findings and review any resulting changes before merging.
  Passing CI or a Codex Security scan does not replace this review.
- Commit each completed, verified task before starting the next. Subagents commit their own finished changes; coordinate the shared index so unrelated work is never included.
- Commit messages follow `scope: summary` (examples in git history: `scss: fix theme var helper broken`). Can also use `scope: subscope: summary`. Keep summaries short and imperative.
- PRs should include: a clear description, related issue links, and screenshots/gifs for UI changes.
- Note any config changes (e.g., `env/*` or Supabase schema) in the PR description.

## Configuration & Secrets
- Runtime config uses `env/<environment>/resources/config.edn`. Dev/test files are private and ignored; production defaults are tracked. Examples and the commented defaults reference live in `config/`; see `doc/configuration.md`.
- Do not commit secrets; prefer env vars or injected config files.

## Deploy
- Follow `doc/docker-builds.md` for `make docker` and PR preview cleanup; `doc/site-provisioning.md` covers separate sites and production/staging services.

- The user grants standing authorization for tested, reviewed migrations and
  bounded feature backfills on this project's Supabase database on SSH host
  `bux`, including production (currently container
  `supabase-db-e840kco0scs04gkcco44w088`). Verify the destination and actual SQL
  before execution. This includes application tables, indexes, constraints,
  functions, triggers, RLS policies and grants needed for the requested feature;
  do not request another confirmation solely for those persistent changes.
  Destructive resets, dropping populated tables/columns, unrelated deletions,
  disabling access controls and other databases need separate authorization.
  Application/container deployment remains outside this standing scope. The
  local Codex `[auto_review].extra_policy` carries matching reviewer guidance;
  managed restrictions and the required denial/override workflow still apply.

- Module specs expose portable `:storage` declarations. Supabase seed tooling,
  public projections and realtime identities derive from that catalog; runtime
  `:supabase :modules` options select seed/reset owners and feature groups.
  SQL provisioning remains separate; see `doc/supabase-tables.md`.

- Site capabilities are resolved from file defaults plus optional CMS overrides before build/provisioning.
  Module specs own storage/content/pages/server declarations; disabled owners stay out of artifacts.
  Read `doc/site-capabilities.md` for build snapshots, dependency validation and retained-data tests.

- `lein with-profile uberjar,provided docs` stages selected source namespaces before
  Codox reads them; use this alias instead of invoking Codox directly.

- `:neovim` owns its WASM runtime/profile and browser workspace; `/neovim` supplies scoped isolation. The Sass build bundles selected `src/` and SCSS sources via `build.neovim-workspace`; keep private config and disabled owners out. See `doc/neovim.md`.

- Visual Markdown editing belongs to the lazy `:markdown-editor` owner. Shared
  authored component rendering uses an explicit public catalog and data arguments;
  SSR renders the same widgets and never runs the editor SDK. See `doc/markdown-authoring.md`.

- Authoring module specs may declare literal `:site/owner`; child
  capabilities follow their selected parent in discovery and packaging. Public
  blog reading stays independent of `:blog-editor` and the lazy visual SDK.

- GitHub's managed `/api/github/settings` dependency and archive share one
  validated server selection. Explicit `:github-user` / `:github-repo` values
  take priority; supply both or neither. Unpublished CMS settings stay omitted;
  partial/invalid GitHub configuration cannot gate unrelated content or startup.
  Provider caches stay separate. Public GitHub reads
  require no token; an optional server token raises the REST archive rate limit.
  Deployment identity and calendar contracts are documented in `doc/activity-panels.md`.
- Modules may declare a server `:start` symbol returning an optional cleanup
  function. The mounted module-services owner starts selected services after
  content/configuration and stops them before those dependencies. GitHub's
  leased, checkpointed Supabase archive runs there; browser paging never imports history.
