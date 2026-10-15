# Native document bootstrap

- `bootstrap.*` is standalone source beside the application frontend, never an
  application/component import. Keep Reagent, re-frame and optional modules out.
- Shadow `:document` generates ignored `resources/public/js/document/head.js`
  and `entrance.js`. Development releases this target before native app watches;
  do not watch it or inject console/preload runtimes. Local `bb prod:build` releases it as `prod-local-document`
  into `target/production/resources/public/js/document`. The head is parser-blocking before image discovery; the body
  runs after completed markup and before deferred hydration. Register both through
  existing Optimus bundles/script wrappers; never inline/slurp their implementation.
- The compiler prefix `DOC_BOOTSTRAP` must remain independent of the app's `$APP`.
  Avoid native browser-global names as namespace roots. Primitive DOM comparisons
  use native identity; keep the generated head small rather than acquiring a runtime.
- Change only document-root attributes/custom properties. React owns image nodes.
  Native route declarations may supply a `:document-target`; completed cold SSR
  holds the existing entrance while `destination.cljs` waits for that target and
  fonts, measures untransformed layout offsets, then releases the same animation
  clock before scrolling smoothly. Missing targets release after a bounded wait; mounted SPA
  targets use the normal smooth scroll. Do not replay the entrance on hydration.
  The sole removal exception is `#ssr-shell`: this unhydrated document-owned
  wrapper lives outside React's `#app`. Remove it at its opacity animation end
  after the hero gate opens, immediately for reduced motion, with bounded cleanup.
  Exact wrapper/source owners retain decode/fade through terminal adoption and clean
  native listeners, observers, frames and timeouts. History Back suppresses lazy
  previews before discovery; later SPA navigation clears the shared return policy.
- Verify compiled output with `test/scripts/document_image_test.cjs`, generated SSR
  fixtures and native browser cold/history/BFCache checks. Source-reader checks alone
  do not establish compiler output, resource ordering or native request behavior.
