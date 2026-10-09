# Verification workflows

Keep this guide and `doc/testing.md` current when test/build entry points change.

- Mount actual Reagent components, dereference subscriptions during render, dispatch
  real events, await lifecycle completion, and unmount in cleanup. Avoid direct
  app-db mutation, dangling subscriptions and forced React flushes.
  Scoped-state tests use native subscription handles and event writes; persistence
  checks cover accepted transactions, rejected transactions and explicit deletions.
- Unit/component tests may replace transport boundaries. Live integration must use
  real routes, module loaders and service bindings; do not inject fixture app state.
- Boot checks dispatch the real startup/stop events and mount/unmount the common
  lifecycle host. Verify repeated starts/remounts do not multiply listeners,
  persistence drains before native history release, and deferred work is cancelled.
- Browser tests use the release Malli registry. Check typed page parameters,
  bounds, booleans, query encoding/extension keys and redacted coercion failures
  with internal validation disabled as well as ordinary contract validation.
- Use owning schemas/constructors in fixtures instead of duplicating contracts.
  Test browser/Node-only schemas in `test/cljs`; JVM tests exercise portable/server
  contracts and generic composition helpers.
  Include malformed input, missing/empty content, failure/retry and stale responses.
- Hydration tests preserve DOM identity and complete expanded/folded state. Browser
  review must also exercise cold/cached loads, both navigation directions, comments,
  SPA Back and external Back, looking for flashes, jumps and replayed animations.
  SSR query variants retain separate HTML/state, reuse only matching fresh public
  data, and never extend freshness without a successful provider read.
- Regenerate Node-rendered fixtures before the browser suite. Check final assertion
  results, running Shadow warnings and browser logs; successful compilation is not
  a passing browser test or proof of live CMS integration.
- Viewport module tests cover offscreen/no acquisition, earlier triggers, manual
  intent, shared CSS/code/init, observer/listener cleanup and delayed silent retry.
  Selective SSR tests preserve native identity through outer re-frame updates and
  await the actual inner commit; renderer metadata stays paired with cached HTML.
- Module CSS checks cover request initiation before Shadow, shared acquisition,
  readiness/failure/retry, inline SSR styles, budget/link fallback and no duplicate
  initial downloads. Hydration preload hints must match Shadow's request kind,
  CORS mode and priority in both the response header and document head.
  Analytics checks cover absence from SSR HTML and browser acquisition only after
  hydration, load, painted idle frames and page binding readiness.
  Check that landing code/styles and FiraCode stay absent on unrelated cold routes;
  opening Search acquires its font, and visible code consumers retain typography.
  Idle prefetch checks include offscreen/hidden hints and restricted connections.
  Data-inspector checks mount its lazy compatibility exports and preserve formatted
  values, full error details and retry. Production audits prove pprint/home helpers
  leave main; a successful source move alone does not establish the boundary.
  Generate both plain and fenced-code Node fixtures. Verify completed Suspense
  boundaries preserve pre/code/span identity before and after deferred hydration,
  including control/ancestor updates while suspended and the actual inner commit.
  Verify temporary transition batching is released on unmount and early navigation.
  Formatter acquisition waits for
  hydration, load, page readiness and painted idle frames. Plain pages never
  acquire it; new SPA code uses the normal loader immediately.
  Compression checks must decode the shell before final data
  is ready. Initial SSR visibility alone
  does not prove hydration; let pending link intent settle before repeating clicks.
- Live motion checks assert simultaneous 250ms linear fades and an opaque sticky
  footer. `motion.html?fallback` removes only the optional native transition API
  in the test driver so the ordinary page-root fallback can be checked too.
  The live driver keeps progress outside the app frame and lets finite motion
  and scrolling settle before the next navigation. Check closed Search focus too.
- Live write tests need disposable records and verified cleanup. Prefer read-only
  browser review for ordinary presentation changes.
- Image upload tests exercise real codecs, timeout/cleanup and Storage publication
  ordering. Browser avatar fixtures test modern-source failure and direct PNG retry;
  they do not establish live Storage behavior. Hook tests use disposable Git indices:
  `bb test:scripts`.

- Icon font checks preserve selected glyph outlines/metrics, verify disjoint core
  and full-font acquisition in a fresh browser, and test real Optimus rewriting,
  font-byte preservation and fingerprinted-only long cache headers.
