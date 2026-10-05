# Verification workflows

Keep this guide and `doc/testing.md` current when test/build entry points change.

- Mount actual Reagent components, dereference subscriptions during render, dispatch
  real events, await lifecycle completion, and unmount in cleanup. Avoid direct
  app-db mutation, dangling subscriptions and forced React flushes.
- Unit/component tests may replace transport boundaries. Live integration must use
  real routes, module loaders and service bindings; do not inject fixture app state.
- Use shared CLJC schemas/constructors in fixtures instead of duplicating contracts.
  Include malformed input, missing/empty content, failure/retry and stale responses.
- Hydration tests preserve DOM identity and complete expanded/folded state. Browser
  review must also exercise cold/cached loads, both navigation directions, comments,
  SPA Back and external Back, looking for flashes, jumps and replayed animations.
- Regenerate Node-rendered fixtures before the browser suite. Check final assertion
  results, running Shadow warnings and browser logs; successful compilation is not
  a passing browser test or proof of live CMS integration.
- Live write tests need disposable records and verified cleanup. Prefer read-only
  browser review for ordinary presentation changes.
