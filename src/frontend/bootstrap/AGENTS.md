# Native document bootstrap

- `bootstrap.*` is standalone source beside the application frontend, never an
  application/component import. Keep Reagent, re-frame and optional modules out.
- Shadow `:document` generates ignored `resources/public/js/document/head.js`
  and `entrance.js`. Local `bb prod:build` releases it as `prod-local-document`
  into `target/production/resources/public/js/document`. The head is parser-blocking before image discovery; the body
  runs after completed markup and before deferred hydration. Register both through
  existing Optimus bundles/script wrappers; never inline/slurp their implementation.
- The compiler prefix `DOC_BOOTSTRAP` must remain independent of the app's `$APP`.
  Avoid native browser-global names as namespace roots. Primitive DOM comparisons
  use native identity; keep the generated head small rather than acquiring a runtime.
- Change only document-root attributes/custom properties. React owns image nodes.
  Exact wrapper/source owners retain decode/fade through terminal adoption and clean
  native listeners, observers, frames and timeouts. History Back suppresses lazy
  previews before discovery; later SPA navigation clears the shared return policy.
- Verify compiled output with `test/scripts/document_image_test.cjs`, generated SSR
  fixtures and native browser cold/history/BFCache checks. Source-reader checks alone
  do not establish compiler output, resource ordering or native request behavior.
