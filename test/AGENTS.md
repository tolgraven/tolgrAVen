# Verification workflows
- Technical UI changes require the exact native layout comparison gate in
  `doc/visual-regressions.md` alongside interaction/motion checks. Capture matching
  narrow/wide states and inspect differences; never refresh approved goldens to
  conceal a regression. Missing/unapproved baselines are verification gaps, not
  passing results. Baseline case/tile JSON may use lossless gzip, with exactly one
  encoding per record and unchanged approval metadata. Geometry mode verifies boxes and computed appearance; pixel
  parity requires matching raster captures. Raster interaction cases retain their
  actual open/hovered viewport before complete-document scrolling; assert the
  transient surface again immediately before its PNG. Never tag later scrolled
  tiles with hover evidence from an earlier viewport. Genuine hover cases use the configured
  Chrome MCP, observed accessibility UIDs verified against native selectors, and
  an unhovered starting target; synthetic mouse events do not prove CSS hover.
  Keep native page/component parents and stylesheet precedence groups (`shell`
  before `modules`) in fixtures. React stylesheet preinitialization must append
  lazy feature styles after the shell, as on actual pages. Compare native CSS
  declarations and triggered keyframes/timing with
  `--require-motion`; settled geometry is not a motion pass. Observe real external
  Back before interaction, including delayed and pseudo motion, without fabricated
  history/restore state. Missing motion baselines remain explicit gaps. Extend `test/visual/suite.json` for new rendered states.
- CMS visual comparisons use `editor-visual.html` and the original commit's
  components, identical seed data/assets and viewport dimensions. Capture actual
  viewports and separate scrolled sections; full-page capture can repaint filtered
  media beyond the active viewport. Inspect edit-mode control geometry separately.
  The fixture's seed URL reads `resources/content-seed.json` directly.
- CV checks cover regular/container creation, automatic editable starter children,
  bounded nesting, changed-parent/count conflicts, retained drafts and account
  isolation. Inspect measured opening/closing frames and viewport centering at
  narrow/wide widths; settled screenshots alone do not prove continuous motion.

- Editor build checks validate the pinned public-entry selection and reject
  unknown/private SDK exports. Production audits reject CodeMirror, Lezer and
  diff-source in the resolved graph, not just in direct dependency declarations.

Keep this guide and `doc/testing.md` current when test/build entry points change.

- `node test/scripts/styles_test.mjs` checks fresh CSS skips, stale inputs,
  overwritten/missing outputs, CMS override edits/removal/errors and
  declaration/tool/font revisions in a disposable
  tree. Startup tests must prove a style failure prevents HTTP from opening while
  successful styles retain the deferred Shadow startup. Document release tests
  prove isolated release-before-watches, scoped HTTP readiness, bounded failure
  and real REPL acknowledgement while compilation is blocked.

- `node --test test/scripts/css_pruning_test.mjs` covers production selector
  liveness, runtime families/states, cross-sheet variable/animation dependencies,
  font aliases and failure-before-write. Verify the actual released asset graph
  and affected routes with pruned CSS too; fixtures alone cannot prove all UI states.
- Cold layout/handler JVM suites require compiled `:document` resources. Build
  them in the isolated test checkout, or reuse its standalone release output; see
  `doc/testing.md`. The full JVM suite also requires the compiled `:ssr` worker
  in that checkout for its actual Node protocol checks.
- Mount actual Reagent components, dereference subscriptions during render, dispatch
  real events, await lifecycle completion, and unmount in cleanup. Avoid direct
  app-db mutation, dangling subscriptions and forced React flushes.
  Render frames do not establish asynchronous source completion; wait for the
  mounted result or result event before asserting loaded/error state.
  Collect related native geometry before reporting assertions: the browser test
  display can reflow the document and move fixtures mounted below its progress.
- Native ref tests cover forwarded React 19 cleanup, legacy nil callbacks,
  replacement/unmount and plain atom/object refs. Keyed state tests
  protect the isolated Reagent 2.0.1 Hiccup metadata compatibility boundary.
- Deferred hydration tests cover cached completion, independently pending
  boundaries, changed props, discarded renders, StrictMode and stale completion
  after navigation/unmount. Require ordinary shell controls to commit before
  unrelated lazy gates release, retaining native SSR nodes and Reagent batching.
  Exercise the real controlled Search input while code is held: check immediate
  DOM value, subscribed query and rendered styled text together. Observe original
  SSR-node removal/hiding continuously; zero raw-fallback count does not prove
  retention. Repeat trusted native typing alongside fixture input events, and use
  `/fixtures/shell-hydration-tests.html` in a fresh document for React lifecycle
  diagnostics that React deduplicates.
  Drain acquisitions queued by released Promise gates before restoring transports.
  Scoped-state tests use native subscription handles and event writes; persistence
  checks cover pure nonpersistent reads, idempotent eager/subscription acquisition,
  late restoration versus edits, accepted/rejected transactions and explicit deletions.
- `bb test:scripts` includes bounded Node document-image adapter fixtures. They
  verify phase/gate ownership and cleanup; real-browser checks verify painted motion.
- Live motion/history checks require the complete app frame inside the outer
  viewport; keep growing diagnostics in their own bounded scroll area below it. Reject clipped scenes before
  making assertions about visible motion. See `doc/testing.md`.
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
- Release browser-test builds include selected module entries through Shadow's
  native namespace list; development uses preloads. Release excludes development
  preloads, so both paths must expose the same lazy owner inventory.
  Hydration fixtures that replace the module map must include each lazy parser
  and renderer owner; retained SSR HTML alone does not prove those owners mounted.
  Keep `:browser-test` in selected release test reader features so test fixtures
  can render server markup through the framework shim without enabling development
  tooling. Optimized hydration checks use `website.nested-hydration-runner`;
  the default display runner mutates a Closure define and is development-only.
- Default development discovery excludes the release-only authored hydration
  suite. Run its configured optimized runner separately; an eager development
  engine cannot exercise that suite's delayed-owner/coercion preconditions.
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
- Infinite-clock alignment acknowledges only its own exact delayed backwards seek.
  Native animationend from that seek retains captured timing/keyframes; unexplained
  ends, changed definitions, another object and later missed resumes still fail.
- Visual captures return one complete geometry fixture per MCP request. Pin suite
  bytes/hash and Git head before browser work; changed suite bytes invalidate
  completion metadata while preserving partial evidence and owned-page cleanup.
  Foreground the owned capture tab explicitly before measurement; MCP focus/visibility
  emulation does not establish physical tab activation. Geometry-only capture parks
  the native pointer outside all iframe bounds and verifies separation plus exact
  child viewport/DPR before and after actions.
- Same-origin frame captures include their own native geometry and CSS motion.
  Preserve sandbox permissions. Late attachment passes only for fully inspected,
  script-disabled documents with no authored/dormant/active motion; otherwise report
  the observation gap. Cross-origin and shadow roots still require their own proof.
- Hover visual states use observed accessibility snapshot UIDs through configured
  Chrome MCP, verify their native selector/`:hover` result, and park the pointer
  on task-owned driver markup before navigation. Keep real keyboard traversal
  distinct from programmatic focus. Dormant motion rules omit ordinary endpoint
  declarations unless an actual captured state exercises them.
- Shell-panel visual fixtures retain native page-sibling placement and module
  presence. Use already-ready service state/public display options for UI-only
  login/register checks; never submit Auth/contact or mutate Settings. Query-link
  controllers and actual provider results need separate live verification.
- Comment-composer visual states retain the real comment parent and lazy owner.
  Seed local form text through events; exercise native mode/Cancel controls and
  assert preview, owner disposal and zero provider I/O. Never Submit or upload.
- Docs visual fixtures require the served snapshot's generated Codox index/topic
  resources. Acquire each new public module's native sheets only in its own case;
  shared-head additions change every case's dormant motion declaration inventory.
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
  Data-inspector checks mount its lazy module exports and preserve formatted
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
  Verify deferred boundaries leave global batching unchanged through unmount and navigation.
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
  deleting only IndexedDB `tolgraven-component-cache` and clearing small browser
  stores. A blocked/failed/timed-out deletion fails the cold check; reset is
  restricted to the dedicated loopback test driver. Network SSR runs one original
  site/hero entrance across hydration; paired restoration stays settled without
  replay; ordinary SPA motion remains enabled.
- Live motion checks assert hero decode before network reveal, one document
  animation clock across hydration for cold/cached SSR, settled paired returns,
  and simultaneous 250ms linear fades and an opaque sticky
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
  original glyph geometry/baseline and native font-glyph rules. Every exported outline
  must fit its viewport, including overhangs. Native glyph checks preserve fractional
  control dimensions and the original alignment inherited by relocated panels.
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

- Optional preview checks withhold code, keep native authored nodes through code
  arrival and candidate changes, skip acquisition for internal/plain content, and
  release native observation on change/unmount.
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

- CPU-control tests use disposable owned burners for native identity, STOP/CONT,
  watchdog death/stall and already-stopped preservation. Keep image/browser timing
  benchmarks separate from these probes and record active resource quotas.

- Test runtime settings live in private `env/test/resources/config.edn`; copy
  `config/test.example.edn` on a new worktree. Test resource paths exclude dev
  resources so test configuration cannot inherit development credentials.

- Managed-media HTTP/codec checks use the ordinary codec-capable dependency prefab
  through `tolgraven-cpu run`. `test/scripts/media_sql_test.sql` creates its own
  roles and fixture schema; run it only in a disposable PostgreSQL container, never
  against a project database. See `doc/media-pipeline.md`.

- Middleware integration checks construct the actual `wrap-base` stack, replacing
  only asset preparation and service I/O. Cover source/jar bytes, conditional and
  range responses, security/cache headers, real form-cookie CSRF, typed API JSON,
  one bounded multipart parse and exact authenticated webhook preflight. Streaming
  gzip must expose the shell before deferred content completes.

- Handler/layout fixtures require generated CSS and the real `:document` bootstrap
  in the same isolated snapshot as the test command. See `doc/testing.md`.
- Prepared-asset checks compare exact optimized metadata, CSS/font URLs and body
  bytes through source and jar resolvers. Reject missing, stale or corrupt output;
  do not replace this proof with a manifest-exists assertion.

- `:neovim-check` mounts the real WASM editor in `/fixtures/neovim.html`; serve with COOP/COEP for active editing, and without them for the fallback. Its plugin/workspace controls exercise real Meta/file imports; Node/BB checks cover preRun bytes, profile failures and source pruning. See `doc/neovim.md`.

- `:github-diff-check` exercises public GitHub revision reads with the real readonly Neovim component. Its focused fixture supplies an eager module loadable; it does not prove full-site navigation or hydration. Its mounted recovery regression checks that a binary first file retains file/patch controls before native startup; public fixture reads begin after that test restores HTTP handlers. Native diff checks in `:neovim-check` verify buffer immutability, diff highlights and absolute line numbers.

- Markdown authoring checks cover data-only component argument round trips, private
  path/expression rejection, feature-pruned generated help, block-safe published
  markup and actual editor normalization/keyboard/source transitions. Vendor CSS
  checks prove selector/palette isolation and relative units. See `doc/markdown-authoring.md`.
- Draft checks cover durable reload, edits during disk reads, account/route isolation,
  named document selection, pending-upload exclusion and real write failure/retry.
  Source checks preserve selection/undo/anchors and accept each native key before
  the next one. Language picker checks update public CMS choices at runtime, retain
  explicit fence tags and exercise keyboard selection; an empty list means auto only.

- Upload-row checks cover measured/invalid/stale byte callbacks, indeterminate
  processing, errors/retry/cancel, ready-but-unpainted retention and preview-owned
  exit. A mocked queue acknowledgment does not prove actual image decode/visibility.

- Liquid-glass focused targets use `target/glass` output. Test real children,
  focus/DOM identity, zero-index refraction, bounded allocations, map reuse,
  failure fallback, cancellation and SSR/hydration. Pixel inspection in Chrome
  establishes SVG backdrop rendering; syntax/compilation alone does not.

- Cleanup tests use disposable Git worktrees and mocked PR/ownership boundaries.
  Cover exact merged heads, private-data recovery, active cache locks and Docker
  output mounts from another checkout (including cache-disabled/legacy builds).
  Native CPU/lifecycle tests remain macOS-only; portable policy tests must still
  run on other platforms. Never use real task worktrees as deletion fixtures.

- `website.macros-loading-test` uses a fresh bounded JVM to verify macro loading
  and annotated argument parsing without initializing Malli; warmed test suites
  cannot establish that compiler boundary.

- `pr-test` exercises PR footer ownership, body replacement and malformed metadata,
  provider failures, selected-PR commit attribution, and the real commit hook in
  disposable Git repositories. Metadata fixtures cover title history, retired
  worktrees, unavailable stores and exclusion of prompts/unrelated sessions.
  It runs with `bb test:scripts`; no browser or
  Java build is involved.
- Readonly Neovim visual states are standalone top-level cases even in geometry
  mode. Keep the native grid/worker and scoped COOP/COEP policy; the unsupported
  document omits isolation. Its native defpage owner needs the target's ordinary
  site-reader hooks, eager development validation and registered route
  subscriptions. Await the fixture's owned disposal hook after capture and on
  failure, asserting worker termination before starting the next session.

- CV visit tests mount the real CV, await persisted snapshot restoration, retain
  legacy flags, and verify SPA/remount/reload suppression plus stale-timer guards
  and exact scroll return. `cv-visit-motion.html?component=cv` measures real
  storage-backed motion from a nonzero origin; reload the same isolated context
  to verify that the tour and focus no longer run. That page explicitly uses
  `init-cv-visit!`; ordinary visual CV/CMS captures use an in-memory visit transport
  while retaining the native mount, durable claim and delayed module events.

- Messaging visual fixtures retain Home/module parents and managed query contracts;
  local drafts use form events and all provider writes fail closed. Include multiple
  GPT prompt/reply pairs. Published widget fixtures use the native Markdown catalog,
  and diagnostics keep their error portal. Experiments acquire their own styles;
  preserve unfinished model-viewer behavior. Pending SoundCloud geometry does not
  prove external player paint or running video behavior.
