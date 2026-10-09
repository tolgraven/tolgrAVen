# Browser and renderer implementation

Keep this guide current with source changes. Detailed APIs/examples live in
`doc/components.md`, `doc/ssr.md`, and `doc/dev-console.md`.

- Feature caches observe re-frame subscriptions through owned, disposable
  reactions. Persistence belongs in the storage lifecycle adapter; do not add
  feature watches to app-db or perform IO in subscription computations.
  Scoped state handles are native re-frame subscriptions; write through
  `>reset`/`>update` events. Accepted transactions enqueue persistence through
  the storage interceptor/effect; no mirrored app-db or writable cursor.
  Local-return capture observes its source subscription in the component lifecycle.

- Feature modules live under `modules/<module>/`; collocate schemas, query
  declarations and `pages.cljc` with the implementation. Browser/Node-only schemas
  use `.cljs`; portable routes/data contracts use `.cljc` and must not require
  browser implementation namespaces. Shared platform contracts remain in `src/cljc`.
- `navigation/` owns routing, preload, transition and scroll adapters and contracts.
  `validation/schema.cljs` assembles browser/Node app-db sections from their owners;
  small single-use shell-state contracts stay inline there.
- Rendered reusable UI lives in `components/`; feature-specific UI lives with its
  module. `component/` is the declaration/lifecycle runtime, not a view inventory.
  Import the owning namespace directly; there is no top-level `views.cljs` shim.
- Render state through `tolgraven.react` subscriptions and change it through events.
  Effects/source adapters own I/O; view functions remain pure.
- Use `<component>` names, `defc` features and `defpage` boundaries. `m/<>` selects
  direct or lazy module exports; it does not add a permanent wrapper. Module
  exports default to proximity activation; `:load-on` and `loader/<trigger>`
  configure intent/earlier triggers. Hidden panels use explicit intent. Completed
  SSR boundaries retain native nodes until activation and inner hydration commit.
  CSS/code failures retry silently once after three seconds before a local error.
- Module `:styles` metadata is available before code acquisition. The shared loader
  starts CSS and JS together and waits for both before readiness; React owns
  stylesheet insertion. Initial inlined route styles already satisfy readiness
  and must not trigger duplicate external requests. Keep feature CSS in `resources/scss/modules` and preserve
  ordinary selector specificity. See `doc/styles.md`.
- A module `:install` hook may restore browser-local caches after code acquisition.
  The loader shares/awaits it before publishing code readiness; ordinary `:init`
  still owns data activation. SSR adapters must not install disk caches or watches.
- Declare managed dependencies at the narrowest owning component/module/page.
  Preloads acquire those same sources; keep pending, empty, failed and cached distinct.
- Subscription-backed Supabase/Strapi results enter app-db through events. Never
  duplicate transport reads in pages or component render functions.
- Reuse appearance/presence/loading features. Hydration and restored data bypass
  loading and entrances; later SPA navigation must restore ordinary motion.
  Appearance classes merge onto native roots; `appear-wrapper` is only a CSS
  class. SSR roots paint visible, without separate child/page entrance keyframes.
- Shared Markdown, full syntax highlighting and mapping code live in the
  `:markdown`, `:highlight` and `:maps` bundles. Keep the full highlighter out of
  Markdown so link previews do not acquire every language. Node renders highlighted
  code inside completed React Suspense boundaries. Copy/wrapping and the page
  hydrate first; the formatter loads/hydrates after the common after-page gate.
  Keep the formatter in its own memoized `defc` so control updates do not replace
  pending SSR nodes. Initial boundaries own transition batching in
  `component/hydration.cljs` until inner commit/unmount; navigation releases it.
  Capture their initial restoration decision without subscribing to completion.
  New SPA code uses the normal loader immediately. Paired local
  returns render completed boundaries too and skip the formatter during bootstrap.
  Local-return rendering uses the lazy `:page-render` bundle;
  the installer remains eager to restore state before the first commit.
- Pretty data and expanded error details live in `:data-inspector`; keep pprint
  out of initial browser code. Shared numeric display uses native fixed decimal
  formatting. Landing story/float helpers belong to `:home`, with lazy compatibility
  exports in shared UI. Preserve prototype implementations when moving ownership.
- The local return adapter renders an isolated state snapshot using the shared
  renderer. HTML and state are inseparable. Do not copy DOM or replay init before
  hydration; retain exact query caches and fold/window state.
- Browser-only APIs belong in lifecycle/effect adapters with cleanup and SSR guards.
  `boot.cljs` installs document persistence/history listeners after app-db setup,
  then site listeners/preferences from the committed root lifecycle. Deferred
  resources, route prefetch and local-return capture share that lifecycle host.
  `listener.cljs` replaces named registrations and removes them by owner on stop;
  do not install browser listeners in `defonce` or other import-time forms.
  See `doc/boot.md` for the stage boundaries.
  Deferred Search owns focus while open and releases it on close/unmount; hidden
  input focus must not drive scrolling during later navigation.
- `browser_resources.cljs` acquires analytics and necessary compatibility scripts
  after hydration, window load, two paint frames and idle with no pending page
  bindings. Production analytics initializes its queue/configuration there too;
  keep its ID, bootstrap, remote scripts and preconnects out of SSR HTML. Modern browsers
  use native smooth scrolling without downloading its polyfill.
  Lazy videos omit sources until visible; their observer owns cleanup, React owns markup.
  Use Shadow reader features for build-specific dependencies, not runtime imports.
- Development console and React profiling implementations live in `env/dev/cljs`.
  Inspector styles compile into `dev.min.css`, linked only by development documents.
  Only builds with the `:dev` reader feature import them; production and SSR use
  small diagnostics/instrumentation boundaries. Debug instrumentation is bounded and inactive when closed. Keep diagnostics out
  of persistence snapshots and do not let diagnostic events trigger page renders.
- Page navigation uses a brief simultaneous opacity crossfade. Native View Transitions
  and the page-root fallback share duration/easing; neither delays the incoming fade.
  Native page snapshots must not cover the sticky footer: retain its own opaque,
  unanimated snapshot layer above the page. DOM z-index cannot outrank that overlay.
  Both request the highest frame rate only when the native Animation API exposes it;
  do not drive page opacity with a JavaScript loop or change browser settings.
- `defpage` enables the optional `:container` layout capability outside its error
  boundary. Merge caller and transition attrs there; loading/content/error share
  the same root; the site shell opts out with `:container false`. No swapper wrapper.
  Fallback roots overlap in one grid cell and both contribute height until cleanup.
- SPA scroll restoration waits for committed page code/data, relevant image/font
  layout and a reachable saved offset on consecutive measured frames. Track pending
  work through the page readiness context and wait for finite layout animations;
  controlled navigation/restoration scrolls must not trigger header hide/show feedback; do not use a guessed completion delay.
- Idle navigation prefetch is limited to adjacent (`rel=prev/next`) and explicit
  `data-preload=true` links. Other internal links acquire on pointer/keyboard
  intent, through the same loader and bindings; never prefetch the whole navbar.
  Start speculation after hydration, page readiness, load and painted idle frames.
  Only visible hints participate; skip hidden tabs, Data Saver and 2G connections.
  The `:home` bundle owns landing views and CSS; the router imports its portable
  pages only. `:styled-input` owns the optional custom field; Search depends on it,
  blog editing acquires it when opened. Markdown and styled-input share one
  independent monospace sheet; font bytes load only for used glyphs.
- After edits check watched Shadow errors and the actual routes in the browser.

- `validation/runtime.cljs` owns declaration checks, module section registration
  and the app-db interceptor. Invalid transactions retain previous state and do
  not run associated effects. Declare event/subscription contracts through the
  existing shim's registration macros; `validation/bindings.cljs` owns argument
  coercion and result checking. Keep handwritten validation and diagnostic effects
  out of domain subscription computations.

- Use inline `defc`/`defpage` `[value :- schema]` for meaningful public inputs;
  a rest annotation validates each remaining value. Keep schemas beside their
  owning component or module; browser-only inputs do not require CLJC. Give persistent component state a `:state {:schema ...}` when it has
  a domain shape, and test normal updates plus rejected transactions.

- Avatar fallbacks must also recover originals that failed before hydration attached
  handlers. Preserve modern-format retries and constrain broken-image alt text to
  the avatar box; a missing upload must not widen post/comment layout.

- Thin build adapters may use `.cljc` solely for Shadow reader features (`:browser`,
  `:ssr`, `:dev`); `.cljs` cannot contain reader conditionals. Keep these separate
  from shared JVM contracts and do not infer a server renderer from the extension.
