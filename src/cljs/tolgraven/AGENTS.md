# Browser and renderer implementation

Keep this guide current with source changes. Detailed APIs/examples live in
`doc/components.md`, `doc/blog-ssr.md`, and `doc/dev-console.md`.

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
- After edits check watched Shadow errors and the actual routes in the browser.

- `validation/runtime.cljs` owns declaration checks, module section registration
  and the app-db interceptor. Invalid transactions retain previous state and do
  not run associated effects. Keep validation out of subscription computations.

- Use inline `defc`/`defpage` `[value :- schema]` for meaningful public inputs;
  a rest annotation validates each remaining value. Keep reusable spec schemas
  in CLJC. Give persistent component state a `:state {:schema ...}` when it has
  a domain shape, and test normal updates plus rejected transactions.
