# Verification workflows

Keep this guide and `doc/testing.md` current when test/build entry points change.

- Mount actual Reagent components, dereference subscriptions during render, dispatch
  real events, await lifecycle completion, and unmount in cleanup. Avoid direct
  app-db mutation, dangling subscriptions and forced React flushes.
- Unit/component tests may replace transport boundaries. Live integration must use
  real routes, module loaders and service bindings; do not inject fixture app state.
- Use owning schemas/constructors in fixtures instead of duplicating contracts.
  Test browser/Node-only schemas in `test/cljs`; JVM tests exercise portable/server
  contracts and generic composition helpers.
  Include malformed input, missing/empty content, failure/retry and stale responses.
- Hydration tests preserve DOM identity and complete expanded/folded state. Browser
  review must also exercise cold/cached loads, both navigation directions, comments,
  SPA Back and external Back, looking for flashes, jumps and replayed animations.
- Regenerate Node-rendered fixtures before the browser suite. Check final assertion
  results, running Shadow warnings and browser logs; successful compilation is not
  a passing browser test or proof of live CMS integration.
- Module CSS checks cover request initiation before Shadow, shared acquisition,
  readiness/failure/retry, inline SSR styles, budget/link fallback and no duplicate
  initial downloads. Hydration preload hints must match Shadow's request kind,
  CORS mode and priority in both the response header and document head.
  Compression checks must decode the shell before final data
  is ready. Initial SSR visibility alone
  does not prove hydration; let pending link intent settle before repeating clicks.
- Live motion checks assert simultaneous 250ms linear fades and an opaque sticky
  footer. `motion.html?fallback` removes only the optional native transition API
  in the test driver so the ordinary page-root fallback can be checked too.
- Live write tests need disposable records and verified cleanup. Prefer read-only
  browser review for ordinary presentation changes.
- Image upload tests exercise real codecs, timeout/cleanup and Storage publication
  ordering. Browser avatar fixtures test modern-source failure and direct PNG retry;
  they do not establish live Storage behavior. Hook tests use disposable Git indices:
  `bb test:scripts`.
