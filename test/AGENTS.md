# Verification workflows

Keep this guide and `doc/testing.md` current when test/build entry points change.

- Mount actual Reagent components, dereference subscriptions during render, dispatch
  real events, await lifecycle completion, and unmount in cleanup. Avoid direct
  app-db mutation, dangling subscriptions and forced React flushes.
  Render frames do not establish asynchronous source completion; wait for the
  mounted result or result event before asserting loaded/error state.
- Native ref tests cover forwarded React 19 cleanup, legacy nil callbacks,
  replacement/unmount and plain atom/object refs. Keyed state tests
  protect the isolated Reagent 2.0.1 Hiccup metadata compatibility boundary.
- Deferred hydration tests cover cached child-before-parent commit, overlapping
  ownership, discarded renders, StrictMode cleanup and stale document completion.
  Preserve the original batching adapter after every test/unmount.
  Scoped-state tests use native subscription handles and event writes; persistence
  checks cover pure nonpersistent reads, idempotent eager/subscription acquisition,
  late restoration versus edits, accepted/rejected transactions and explicit deletions.
- Unit/component tests may replace transport boundaries. Live integration must use
  real routes, module loaders and service bindings; do not inject fixture app state.
- Boot checks dispatch the real startup/stop events and mount/unmount the common
  lifecycle host. Verify repeated starts/remounts do not multiply listeners,
  persistence drains before native history release, and deferred work is cancelled.
  Listener checks exercise capture/passive/once, independent lifecycle acquisitions,
  stale replacement cleanup and rollback of partially installed groups. Return
  policy tests use ordinary data; Cache API and live service-worker checks protect
  one-time consumption, build compatibility and actual document restoration.
- Browser tests use the release Malli registry. Check typed page parameters,
  bounds, booleans, query encoding/extension keys and redacted coercion failures
  with internal validation disabled as well as ordinary contract validation.
- Use owning schemas/constructors in fixtures instead of duplicating contracts.
  Test browser/Node-only schemas in `test/cljs`; JVM tests exercise portable/server
  contracts and generic composition helpers.
  Include malformed input, missing/empty content, failure/retry and stale responses.
- Cached public-data checks read through the native alpha subscription before
  ordinary subscriptions can warm their shared cache; verify the first render
  while the SDK is still unavailable.
- Reload checks cover worker-paired exact layout and network fallback display
  restoration, including explicit interaction while disk restoration is pending.
  Preview returns retain visited URLs but discard active surfaces/DOM registrations;
  check pointer/focus suppression and BFCache dismissal without entrance replay.
- Hydration tests preserve DOM identity and complete expanded/folded state. Browser
  review must also exercise cold/cached loads, both navigation directions, comments,
  SPA Back and external Back, looking for flashes, jumps and replayed animations.
  SSR query variants retain separate HTML/state, reuse only matching fresh public
  data, and never extend freshness without a successful provider read.
- Regenerate Node-rendered fixtures before the browser suite. Check final assertion
  results, running Shadow warnings and browser logs; successful compilation is not
  a passing browser test or proof of live CMS integration.
- Instance-feature checks cover feature-vector/map normalization before validation,
  callbacks, SSR and hydration, preserved domain vectors, disabled acquisition/config
  functions, dynamic off/on/off cleanup and native/local-state identity, StrictMode replay, current
  lifecycle callbacks, cleanup exceptions, fragment presence, font nil roots, and
  mandatory page/data boundaries. Observe actual subscription/effect boundaries;
  redefining first-class shim functions does not intercept their macro expansions.
- Viewport module tests cover offscreen/no acquisition, earlier triggers, manual
  intent, shared CSS/code/init, observer/listener cleanup and delayed silent retry.
  Selective SSR tests preserve native identity through outer re-frame updates and
  await the actual inner commit; renderer metadata stays paired with cached HTML.
- Module CSS checks cover request initiation before Shadow, shared acquisition,
  readiness/failure/retry, inline SSR styles, budget/link fallback and no duplicate
  initial downloads. Layout fixtures load the module's declared stylesheet
  dependencies, rather than relying on the shell sheet or earlier tests.
  Hydration preload hints must match Shadow's request kind,
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
  Include untagged blocks, explicit C/Clojure tags and generic inline snippets;
  check Bruvbox tokens, phrasing-only copy controls and the blog-only fallback.
  Code gestures must cancel selection, multi-click, drag, pointer exit/reentry and
  touch scroll during the press. A completed tap must survive post-release motion
  while selection/multi-click can still cancel its confirmation. Cover both normal
  Reagent handlers and the temporary SSR bridge; verify bridge removal on commit.
  Linked snippets must retain navigation without nested controls. Test raw-pre
  provenance collisions and option forwarding through the normal Markdown wrapper;
  copying folded/numbered blocks must preserve the full original source.
  Verify temporary transition batching is released on unmount and early navigation.
  Formatter acquisition waits for
  hydration, load, page readiness and painted idle frames. Plain pages never
  acquire it; new SPA code uses the normal loader immediately.
  Compression checks must decode the shell before final data
  is ready. Initial SSR visibility alone
  does not prove hydration; let pending link intent settle before repeating clicks.
- Font checks mount native roots, gate the load effect, then release the actual
  FontFaceSet request. Verify shared acquisition, visible fallback, replacement
  only while transparent, native node identity and fallback dimensions. Include
  failure/stale completion, disabled intent, reduced motion and local returns.
- Live drivers share the test-origin reset adapter: close the outgoing frame,
  then clear saved documents through the worker's serialized protocol before
  clearing browser storage. Cold SSR and paired restoration stay visible without
  root entrance replay; ordinary SPA motion remains enabled.
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

- Icon SVG checks cover literal/reader-safe declarations, shared/module ownership,
  original glyph geometry/baseline and font-free masks. Every exported outline
  must fit its viewport, including overhangs; expanded masks retain layout metrics.
  Python checks require the
  optional authoring fontTools environment described in `doc/styles.md`.
  Icon font checks preserve selected glyph outlines/metrics, verify disjoint core
  and full-font acquisition in a fresh browser, and test real Optimus rewriting,
  font-byte preservation and fingerprinted-only long cache headers.

- Snapshot transport checks use a local HTTP server to prove TCP reuse across
  bound tasks, fresh request credentials and pool closure after success/failure.
  Live provider timing checks perform public GETs only and never log payloads/keys.

- Deferred coercion checks compare native data merge expressions with Malli's
  eager merges, exercise the same router before/after adapter installation and
  preserve invalid-route/redacted-error/query round trips. Production checks must
  withhold the coercion chunk, prove SSR hydrates without it, then test early
  navigation and retries. Audit every Malli namespace out of the main bundle.

- Readable preview checks cover semantic extraction, malformed/oversized documents,
  public-address DNS enforcement, redirect bounds, deduplication and frame policies.
  Mounted browser checks render text safely, delay miniatures and suppress blocked
  frames; live review must distinguish static article extraction from JS-only pages.

- Development diagnostics checks must cover source catalog ownership and disabled
  production endpoints, exact source-map positions without generated fallbacks,
  bounded/redacted actual values, native query ownership, capture feedback and
  lifecycle cleanup. Verify picker/pop-out/source navigation in an isolated dev
  instance, and audit development namespaces out of browser/Node release graphs.

- Supabase catalog checks cover default/disabled owners, optional comments,
  custom table discovery, dependency ordering, JSONB ownership, REST/JDBC parity
  and explicit reset scope. Replacement transports do not establish live DB/RLS
  behavior; use a disposable PostgreSQL database for actual reset/import checks.

- Site capabilities are resolved from file defaults plus optional CMS overrides before build/provisioning.
  Module specs own storage/content/pages/server declarations; disabled owners stay out of artifacts.
  Read `doc/site-capabilities.md` for build snapshots, dependency validation and retained-data tests.

- CMS deploy-action provisioning checks run `test/scripts/cms_actions_test.php`
  inside the installed Coolify container with helper, CMS UUID and web UUID arguments.
  They roll back all credential/environment writes and never trigger deployments.

- Test runtime settings live in private `env/test/resources/config.edn`; copy
  `config/test.example.edn` on a new worktree. Test resource paths exclude dev
  resources so test configuration cannot inherit development credentials.
