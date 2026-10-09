# Browser and renderer implementation

Keep this guide current with source changes. Detailed APIs/examples live in
`doc/components.md`, `doc/blog-ssr.md`, and `doc/dev-console.md`.

- Feature caches observe re-frame subscriptions through owned, disposable
  reactions. Persistence belongs in the storage lifecycle adapter; do not add
  feature watches to app-db or perform IO in subscription computations.

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
  direct or lazy module exports; it does not add a permanent wrapper.
- A module `:install` hook may restore browser-local caches after code acquisition.
  The loader shares/awaits it before publishing code readiness; ordinary `:init`
  still owns data activation. SSR adapters must not install disk caches or watches.
- Declare managed dependencies at the narrowest owning component/module/page.
  Preloads acquire those same sources; keep pending, empty, failed and cached distinct.
- Subscription-backed Supabase/Strapi results enter app-db through events. Never
  duplicate transport reads in pages or component render functions.
- Reuse appearance/presence/loading features. Hydration and restored data bypass
  loading and entrances; later SPA navigation must restore ordinary motion.
- Shared Markdown/highlighting and mapping code live in the `:markdown` and
  `:maps` bundles. Declare bundle dependencies for consumers that must have them
  before hydration. Local-return rendering uses the lazy `:page-render` bundle;
  the installer remains eager to restore state before the first commit.
- The local return adapter renders an isolated state snapshot using the shared
  renderer. HTML and state are inseparable. Do not copy DOM or replay init before
  hydration; retain exact query caches and fold/window state.
- Browser-only APIs belong in lifecycle/effect adapters with cleanup and SSR guards.
  Use Shadow reader features for build-specific dependencies, not runtime imports.
- Development console and React profiling implementations live in `env/dev/cljs`.
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
