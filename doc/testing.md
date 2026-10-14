# Test boundaries and application workflows

A passing unit suite is not evidence that the deployed application, Strapi,
Supabase, or hydration works. Keep these layers distinct when reporting results.

| Suite | Boundary exercised | Replacements allowed? |
| --- | --- | --- |
| `test/clj` | Pure transformations, request/permission contracts, Ring handlers, SSR cache/concurrency and streaming | Yes: these are unit/contract tests. The worker protocol test also starts the real Node renderer with fixture input. |
| `test/cljs/*_test.cljs` | Mounted components, events/subscriptions, adapter lifecycles, persistence, error recovery, renderer/hydration contracts | Yes: explicitly isolated unit/component tests. A fake HTTP/SDK boundary does not make a test live integration. |
| `bb ssr:fixtures` | Actual compiled Node renderer, request isolation, escaping, fixtures for browser hydration | Fixture snapshots, not live CMS/database integration. |
| `test/scripts/*_test.py` | Deployment/provisioning decisions and shell entrypoint contracts | Yes: SSH, Docker, Coolify, clock and HTTP commands are faked. No deployment occurs. |
| `test/browser/live.html` | Real server, compiled app, router/module loader, CMS/database bindings, hydration and browser navigation | **No mocks, injected application state, fake responses, or replacement loaders.** |
| `test/sql` | Installed SQL operations/imports, transactions, ownership and RLS in PostgreSQL/Supabase | Real database, controlled fixture records. Use a disposable database. |
| `test/scripts/provision_wiring_test.php` | Installed Coolify models and actual environment-variable wiring | Real Coolify database; writes roll back. This is an explicit infrastructure check, not part of the local unit command. |

## Unit and component checks

On a new checkout/worktree, copy `config/test.example.edn` to
`env/test/resources/config.edn`. The test profile loads that private file and
excludes the dev resource directory so dev credentials are not inherited.
See [Runtime configuration](configuration.md) for defaults and integration keys.

Backend handler/layout fixtures need generated CSS and the real `:document`
bootstrap. The full suite also starts the real `:ssr` Node worker. In an isolated
agent snapshot, build those prerequisites and run the
suite together so each command sees the same output. Use the existing Shadow
worker when a target is watched; the isolated agent snapshot preserves that output:

```sh
/Users/tol/.local/bin/tolgraven-cpu run -- sh -c 'npm run build && lein with-profile prod,provided run -m shadow.cljs.devtools.cli release document ssr && lein with-profile +project/test test'
bb test:scripts
```

Focused pure backend tests can omit those asset prerequisites. Preserve the
existing watched output; a placeholder bootstrap does not verify layout behavior.

The script suite includes bounded Node fixtures for the initial image adapter
(`node --test test/scripts/document_image_test.cjs`). They exercise native phase/gate
ownership and cleanup; mounted/browser checks below still establish actual pixels
and transitions.

Browser suite:

Build `:document` first as above; its scripts are required by generated HTML.

1. Compile the existing `:ssr` target. If it is already watched, use that Shadow
   worker (`shadow.cljs.devtools.api/watch-compile!`), not a competing compilation.
2. `bb ssr:fixtures` regenerates browser hydration fixtures from
   the current renderer. Do not test against stale generated HTML.
3. `lein with-profile +project/test run -m shadow.cljs.devtools.cli compile app-test`
   (use its existing worker instead if that target is watched).
4. `bb test:browser`, then open `http://127.0.0.1:4002/`.
   Inspect final assertion counts and failures, not just compilation.

`:app-test` discovers ordinary `website.*-test` namespaces through the Shadow
browser runner; the authored hydration suite runs separately in release mode,
where the validation engine and component owners actually begin deferred.
The obsolete Doo entry point has been removed.
Selected optimized hydration builds retain `:browser` and `:browser-test` reader
features and use `website.nested-hydration-runner` with the authored and nested
hydration namespaces. The test-only feature makes server fixture rendering
available through the framework shim while keeping development tooling absent.
The default test display runner is for development: it mutates a Closure define
and cannot be compiled with advanced optimizations.
`workflow_unit_test.cljs` replaces the misleadingly named `integration_test.cljs`.
Ring request fixtures use `ring-mock` from `:project/test`.
Browser/Node-only state and component schemas are tested in `test/cljs`; portable
contracts and generic schema composition helpers are also tested on the JVM.

`:app` and `:app-dev` share their output directory. Use an isolated output directory
for production compilation during development, or pause the development watch and
restore its output before resuming browser checks. Watching a different build ID
does not make overlapping output safe.

Application subscription tests mount a Reagent consumer, dereference the real
subscription during render, change state through registered events, and unmount
in cleanup. `test_support.cljs` supplies this fixture. It reads the consumer's
rendered output rather than constructing a synthetic reaction or reading app-db.
Preload ownership checks use re-frame's tooling API to verify disposal.

Mounting and render updates are asynchronous. The shared fixture mounts once
through `reagent.dom.client`, then updates a Reagent atom containing its form.
Tests await Reagent render turns or a rendered condition; they never call
`ReactDOM.flushSync` or a raw React root's render method. The small `test_async.clj`
bridge uses the existing core.async dependency for sequential asynchronous test
bodies. Use explicit `with-meta` for keyed vector fixtures inside these blocks:
core.async's state-machine transformation does not retain literal vector metadata.

Adapter unit tests may call the adapter directly and explicitly dispose its
returned readers: that is the interface under test. Mocked SDK tests establish
query construction/batching and callback handling, not actual database filtering,
RLS, login, or network transport. Low-level snapshot/storage tests may seed and
inspect isolated app-db fixtures; fixture component bodies still use subscriptions.
Use events for the actual operations being tested (including explicit deletion).

Promises and synthetic DOM events in the browser test driver are test adapters,
not a second application data flow. Tests must clean up roots, readers, timers,
listeners and replacements even after failure. For asynchronous work wait on a
result, rendered condition or event settlement; a zero-delay timer is only useful
when deliberately testing a queue tick. Animation tests may wait for the bounded
animation duration they explicitly configure.

## Live application integration

Start the normal application (`lein repl`, watched `:app-dev` and `:ssr`, and CSS)
with working Supabase and Strapi configuration. Then:

```sh
bb test:integration --app http://127.0.0.1:4000
```

For the production server in a local checkout, build CSS and release the document bootstrap,
browser, renderer and return-worker into separate output first, then start it over HTTP:

```sh
bb prod:build
PORT=4011 lein with-profile prod run
bb test:integration --app http://127.0.0.1:4011
```

The `prod` Lein profile overlays `config/prod-local.edn` on the production defaults:
loopback listening, HTTP session/CSRF middleware, and the local SSR renderer at
`target/production/ssr/site.js`. HTTPS cannot connect to that HTTP listener. The packaged
server keeps its TLS proxy policy and `/app/ssr/site.js` path. The `run` command
starts the backend; it does not compile missing frontend/renderer outputs.
`bb prod:build` releases all four Shadow targets sequentially with separate build
IDs/caches, then prepares the existing Optimus inventory in the same isolated
resource root. Document/browser/return-worker resources live in `target/production/resources`,
which the `prod` profile places ahead of shared resources; the watched `app-dev`
files remain untouched. Startup checks the release browser manifest and both
`/js/document/head.js`, `/js/document/entrance.js` and prepared inventory presence before initialization logging
and Optimus asset loading. Missing files report `bb prod:build` and their paths;
a development browser manifest is rejected instead of serving the reconnect HUD.
The prepared manifest validates the selected build and source digests when the
handler is constructed; missing or stale output never triggers runtime rebuilding.
The released main script remains a validated source, but is served at Shadow's
hashed URL directly. Page preloads/scripts and the rendering worker share that
URL; Optimus must not create a second main bundle or rewrite its source-map URL.
Agent builds use the
[CPU controls](resource-controls.md); copy their generated `target/production`
directory back from the reported isolated checkout before starting the server.
Without configured providers this server uses seed content, which cannot pass
live CMS/database integration. Export the real provider settings for live checks.

For a locally published production container, add `--forwarded-proto https` to
model its usual TLS-terminating proxy. Otherwise Ring's secure defaults redirect
the plain HTTP test connection to HTTPS. Add `--verbose` to log request paths and
statuses without query parameters. The container should use a separate local port
and the same configured public providers; do not replace their responses.

Open `http://127.0.0.1:4003/__tests/` and press **Run checks**. The driver loads the
real site in an iframe, waits for ordinary controls to respond after hydration,
then clicks actual permalink, tag, landing and blog links and uses browser Back.
It requires the bootstrap response to identify its source as `strapi` via
`X-Content-Source`; offline seed mode is a failure, not a live CMS pass.
It checks real content, image decoding, shared heading identity, closed initial
search, and that SPA navigation retains the document. No test functions are
loaded into the application's CLJS runtime. The proxy forwards the application's
response bytes and flushes streaming chunks without replacing them.

`/__tests/motion.html` checks the original network SSR entrance through hydration,
hero readiness before reveal, and settled paired returns without entrance replay, comment layout, simultaneous 250ms linear page fades and the
opaque sticky footer. Add `?phone` for a 24.375rem test frame, or `?fallback` to remove the optional native transition API in the test driver and exercise ordinary fallback
navigation with the same content. `__tests/scroll.html` checks SPA and document
Back restoration. Run these on separate test origins if they overlap in time.

The separate localhost origin is exclusively for these tests; the runner clears
that origin's storage before a run, including saved return documents through the
worker's serialized clear protocol after the outgoing page closes. It does not
clear your regular port-4000 session. Published blog content with a tagged post
is required. Missing content or an unavailable service fails the check; do not substitute a fixture to make
it pass. These are read-only public workflows: they do not publish posts, submit
comments/votes, log in, or alter live records.

Also inspect a real top-level browser window, especially Safari/private browsing:
iframe checks do not establish bfcache, Safari image policy, visual continuity,
exact scroll restoration, or the absence of transient flashes. Check cold direct
permalinks, hydration, both directions of landing/blog navigation, post-to-post
navigation, and Back from an external site. Do not report those checks as passed
unless performed. Failure injection/retry component tests remain unit tests;
service outage integration needs a controlled test environment.

## Database and infrastructure checks

Apply the actual application schema to a disposable Supabase-compatible database,
then run SQL checks with `psql -X -v ON_ERROR_STOP=1 ... -f test/sql/<file>.sql`.
`supabase_operations_test.sql` and `supabase_cutover_test.sql` roll back fixture
changes; sequences can still advance. The auth-import Python test uses the real
import SQL across commits and removes its fixture accounts in `finally`, so it
must never target a normal application database. It does not prove a GoTrue login.

The Coolify wiring PHP check targets the explicitly listed application/service
UUIDs and requires the installed helper/container. Inspect those targets before
running it; a local mocked provisioning test does not replace it. Keep SQL and
Coolify results separate from the unit counts, and report unrun checks explicitly.

## History scroll checks

With the live proxy running, open `/__tests/scroll.html`. This separate check
exercises actual SPA links, Back, leaving the application document, and returning
at the saved position. It does not claim to validate CMS configuration.

Initial SSR leaves browser scroll restoration enabled. Same-document navigation
uses the application's saved positions; leaving the document hands restoration
back to the browser. A return explicitly marked `data-restore` by the server has
skipped SSR in favor of persisted client content, so bounded mutation and resize observers
retains its saved target until the document has enough height. Hydration and
BFCache pageshow do not independently force a scroll. User input, another
navigation, pagehide, a settled layout, or the timeout disposes the observers. A brief quiet
period after reaching the target covers subsequent React commits and image layout;
pending eager images/fonts retain observation within the same bounded deadline.

## Browser bundle declarations

`website.build.browser/process` delegates to Shadow's browser target after
reading `src/frontend/website/modules/*/module.cljs`. Entries need a literal
`(def spec {:id :feature ...})`. Their namespace can declare
`{:bundle/depends-on #{:main :other-feature}}`; the default is `#{:main}`.
Directories without `module.cljs` remain ordinary source organization. `:main`
is the eager app entry configured in `shadow-cljs.edn`. The runtime lazy-load map
is generated from the same declarations, including aliases such as `:test`.

No generated configuration needs committing. Restart the browser watcher after
adding/removing entries or changing bundle dependencies. The build reads source
as data with evaluation disabled; it never loads browser code into the JVM.

Production `:app` enables Shadow's `:module-hash-names`. The server reads
`manifest.edn` for the Optimus main input and route preloads; Shadow owns lazy
chunk URLs. Successful hashed chunk responses are immutable for a year. The
SDK uses its own fingerprinted Optimus bundle. Development and test builds
retain their ordinary output names.

Reusable Markdown, full syntax highlighting and mapping libraries have explicit shared
`:markdown`, `:highlight` and `:maps` bundles, avoiding hoisting into `:main`. Preview consumers
load Markdown as a code dependency before hydration; Node uses the same component
synchronously. Blog also declares `:highlight` so server-rendered code blocks
hydrate immediately; other consumers retain readable plain code during acquisition.
The local-return installer stays eager, but its React server
renderer is in `:page-render` and is acquired only after the cache connects.

Run `bb audit before` and repeat with an `after` label to
compare actual production artifacts (raw, gzip level 9 and Brotli quality 11).
Each build has an isolated build ID/cache and writes to `target/bundle-audit/<label>`;
watched app assets are untouched. `sources.edn` records module ownership and
`sizes.json` records bytes. The audit rejects diagnostics, mapping and server
renderer/highlighting implementations in `:main`. It keeps the normal production
entry/profile, optimizations and reader features. Total bytes measure every
bundle, not the initial route's network cost; lazy code remains part of that total.
Public reading bundles also reject editor implementations and SDKs. The portable
`modules/blog_editor/pages.cljc` route declaration is allowed in the eager router;
its authoring implementation remains in the deferred editor module.

To run the route checks against those actual advanced-compiled files while keeping
the HTTP/data server unchanged:

```sh
bb test:integration --app http://127.0.0.1:4000 \
  --port 4018 --bundle-dir target/bundle-audit/final
```

Open `http://127.0.0.1:4018/__tests/`. Only the compiled JS is served from the
audited directory; other requests use the live server. The manifest is a whitelist,
so missing production chunks cannot silently fall back to development output.
This verifies production frontend code with the current backend configuration;
it does not establish production server packaging or deployment behavior.

Development startup releases `:document` before its ordered application watches.
It must stay optimized even beside `:app-dev`: unoptimized independent CLJS
runtimes share Closure globals and corrupt local-return hydration. Document-only
requests wait for this release with a bounded readiness barrier; the REPL does
not wait for compilation. Focused startup tests cover ordering, failure, timeout
and real nREPL acknowledgement during a blocked document release.

For isolated live bootstrap validation, the integration proxy accepts
`--document-dir PATH` pointing at the capped build's actual document output.
Add `--style-dir PATH` for actual isolated stylesheet output. Only their declared
`/js/document/` and `/css/website/` paths are overridden; application responses,
state, styles and app chunks still come unchanged from the existing server. This
checks a standalone release without overwriting a watched runtime or restarting
the user's REPL.
