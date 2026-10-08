# Browser and renderer implementation

Keep this guide current with source changes. Detailed APIs/examples live in
`doc/components.md`, `doc/blog-ssr.md`, and `doc/dev-console.md`.

- Collocate module-owned `schema.cljc` and `pages.cljc` with the module here.
  These portable declarations are consumed by JVM tooling and routers too; keep
  browser-only implementation dependencies out of them. Shared platform contracts
  and schema infrastructure remain in `src/cljc`.
- Render state through `tolgraven.react` subscriptions and change it through events.
  Effects/source adapters own I/O; view functions remain pure.
- Use `<component>` names, `defc` features and `defpage` boundaries. `m/<>` selects
  direct or lazy module exports; it does not add a permanent wrapper.
- Declare managed dependencies at the narrowest owning component/module/page.
  Preloads acquire those same sources; keep pending, empty, failed and cached distinct.
- Subscription-backed Supabase/Strapi results enter app-db through events. Never
  duplicate transport reads in pages or component render functions.
- Reuse appearance/presence/loading features. Hydration and restored data bypass
  loading and entrances; later SPA navigation must restore ordinary motion.
- The local return adapter renders an isolated state snapshot using the shared
  renderer. HTML and state are inseparable. Do not copy DOM or replay init before
  hydration; retain exact query caches and fold/window state.
- Browser-only APIs belong in lifecycle/effect adapters with cleanup and SSR guards.
  Use Shadow reader features for build-specific dependencies, not runtime imports.
- Debug instrumentation is bounded and inactive when closed. Keep diagnostics out
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
- After edits check watched Shadow errors and the actual routes in the browser.

- `validation/runtime.cljs` owns declaration checks, module section registration
  and the app-db interceptor. Invalid transactions retain previous state and do
  not run associated effects. Declare event/subscription contracts through the
  existing shim's registration macros; `validation/bindings.cljs` owns argument
  coercion and result checking. Keep handwritten validation and diagnostic effects
  out of domain subscription computations.

- Use inline `defc`/`defpage` `[value :- schema]` for meaningful public inputs;
  a rest annotation validates each remaining value. Keep reusable spec schemas
  in CLJC. Give persistent component state a `:state {:schema ...}` when it has
  a domain shape, and test normal updates plus rejected transactions.

- Avatar fallbacks must also recover originals that failed before hydration attached
  handlers. Preserve modern-format retries and constrain broken-image alt text to
  the avatar box; a missing upload must not widen post/comment layout.
