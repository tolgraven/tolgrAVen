# Dependencies and build boundaries

Use locked npm installs (`npm ci`) and the explicit Maven versions in
`project.clj`. Upgrade deliberately, inspect the resolved graph, and test both
Node SSR and the browser: a successful JVM test run cannot validate React or
ClojureScript changes.

## Ownership

- Shadow owns ClojureScript, Closure Compiler and its implementation libraries.
  Do not add a second Closure artifact or copy Shadow's transitive dependencies
  into the application list. Shadow and browser libraries live in the
  `:provided` profile: available to
  compilers and the default development REPL, excluded from the packaged runtime.
  Explicit build commands use `with-profile prod,provided` or
  `with-profile uberjar,provided`; dependency `:scope` alone does not remove JARs
  from a Lein uberjar. The managed CLJS version must match Shadow: Codox otherwise injects its legacy analyzer despite Shadow
  already supplying one. The separate `:codox` profile caches documentation-only
  dependencies; Docker rejects incomplete documentation output.
- Application dependencies remain explicit when application code uses them.
  Reitit is assembled from the ring, middleware, Malli, frontend, development
  diagnostics and Swagger modules used here, rather than its umbrella artifact.
- `:managed-dependencies` aligns the Jackson family, Commons IO and Clojure's
  cache/memoize/priority-map/rrb-vector families. These prevent older transitive
  requirements from winning by classpath traversal order. Recheck pins on upgrade.
- React and React DOM must use the same version. Shadow's npm launcher matches
  its JVM version. Node 22.12 or newer is required by the development tooling.
- The Supabase browser UMD file comes from the exact locked npm package.
  `npm run vendor:sync` verifies the installed version and copies it;
  `npm run init` and `npm run build` include this step. Do not update the checked-in
  browser SDK independently of the lockfile.
- The Maven repository uses ordinary HTTPS for reads. `s3-wagon-private` remains
  available through `:s3-publish` for publishing patched forks to S3 without
  loading its AWS tooling into every build. The project does not need
  `lein-shell`; the personal `deploy-private` alias still uses it.
- CSS tools are local development dependencies. `npm run init` installs from the
  lockfile; it does not install global tools.
  Production pruning pins PurgeCSS with patched selector-parser and maintained
  glob overrides; verify its raw/file APIs and both npm audit scopes before
  changing those pins. CSS variable reachability uses PostCSS's value parser.

## Optional and retained libraries

The development CIDER Lein plugin supplies its middleware dependency. The default
`lein repl` alias trampolines into `website.dev.terminal`, which uses nREPL's
server adapter with the CIDER middleware list, Piggieback and Shadow, and calls
the unchanged `user/start`. The terminal client is neorepl v0.3.0, pinned by full
Git SHA in `config/neorepl.edn`, running in a separate Babashka process so JLine
and client dependencies do not enter the application JVM/prefab. Trampoline
preserves terminal descriptors; do not replace it with a piped `lein run`.
`lein repl-classic` removes the alias and calls the original Lein task, preserving
custom `:repl-options`/editor injections and transports outside the terminal
adapter's standard host/port modes. When adding middleware, keep the terminal
adapter and original `:repl-options` aligned. The official Clojure enhanced REPL
supports Clojure 1.12+, but its default value renderer has plain output; neorepl
was chosen for highlighted values and explicit CIDER/Shadow session support.

The REPL handler explicitly lists Piggieback and Shadow to satisfy their middleware
ordering dependencies. Shadow's separate nREPL server explicitly includes
Piggieback through `shadow-cljs.edn` too; keep both server configurations aligned.
CIDER 0.63.1 includes Piggieback when constructing its internal handler, avoiding
its former missing-middleware warnings without filtering startup output.
The original Lein task allows three minutes for cold application initialization;
the terminal adapter starts its client after that initialization completes.
Shadow startup runs on a background task after a one-second deferral, so initial
`:return-worker`, optional `:ssr`, and `:app-dev` compilations do not block the
REPL acknowledgement. Watches compile in that order rather than competing for
CPU. Source-capability checks read one settings snapshot per build, rather than
reloading it for every source file. `(cljs-repl)` waits for queued startup and
selects in the calling nREPL session; a failed background startup is logged and that call retries it.
Application stop/restart retains Shadow workers as before; Shadow owns their
JVM shutdown lifecycle. Repeated starts share any pending startup task.

`luminus-undertow` supplies the Ring HTTP server through its Undertow adapter.
Undertow's XNIO workers use JBoss Threads for execution. Managed Undertow 2.3.26
and JBoss Threads 3.10.1 retain the adapter's 2.3 API family and replace the old
executor's `sun.misc.Unsafe.objectFieldOffset` implementation. Align JBoss Logging
with the executor's required version too. The prefab uses Java 21; local startup
also checks the newer JDK rather than suppressing its warnings. JDK 27 still
reports an independent Unsafe call in Closure Compiler's bundled protobuf code;
this belongs to the compiler graph, not the HTTP executor.

Default development starts the custom console and keeps re-frame tracing
available to re-frame-pair. 10x and re-frisk are opt-in with `:legacy-debug`;
see [the debugging guide](re-frame-pair.md). This avoids loading two additional
inspectors into every development page.

`:experiments` retains java.jdbc for its SQL prototype, HoneySQL 2
(`honey.sql` / `honey.sql.helpers`), and optional diff/Ring middleware dependencies
for preserved prototypes. Its `experiments/clj` source path keeps the SQL prototype
out of normal application compilation. Enable the profile before working on it.
`lein with-profile +experiments test website.sql-prototype-test` checks SQL generation;
`SUPABASE_CATALOG_TEST_DB_URL` adds temporary-table PostgreSQL round trips only
against a disposable test database.
CodeMirror and React Player dependencies have been removed; their preserved
frontend examples require restoring compatible dependencies before use.

`ring-mock` belongs to `:project/test`. The obsolete Doo runner, humane-test-output,
re-pollsive and direct Fipp dependency have been removed. Fipp remains transitively
through Malli; Puget remains in the optional legacy debugging graph.

For an S3-hosted fork, enable the publishing profile with
`lein with-profile +s3-publish deploy <repository-name>`. Configure that named
deployment repository and its S3 endpoint in the fork's project or your private
Lein profile; keep credentials outside source control. The application's existing
HTTPS repository continues to resolve artifacts without the wagon. A profile in
this project is not automatically inherited by another fork's project: copy the
small `:s3-publish` declaration there, or keep it in your private Lein profiles.

The workstation's AWS CLI publishing alternative is recorded in
[lein-profiles.clj](lein-profiles.clj), matching `~/.lein/profiles.clj`. It keeps
`lein-pprint`, `lein-ancient` and `lein-shell`, without Portal. This is a personal
profile example; it is not automatically loaded by this project.

Run `lein deploy-private` from the patched library's checkout. The alias downloads
the existing `s3://tolgraven/m2/releases/` repository into `.deploy-m2`, then runs
`clean` and `deploy private-local` as direct Lein tasks. Seeding the staging
repository preserves previous versions in Maven metadata. It uploads with the
AWS CLI's `hetzner` profile and public-read ACL, then trashes staging only after
success. Any failed step stops the sequence and leaves staging available for
inspection. Neither sync uses `--delete`; serialize publishing to avoid
concurrent metadata updates.

`private-local` belongs in `:deploy-repositories`: it is a file repository used
only for staging uploads, not dependency resolution. This route needs
`lein-shell`, AWS CLI and macOS `trash`, but does not need `s3-wagon-private`.
AWS credentials stay outside the Lein profile. The HTTPS read URL matches the
bucket and prefix used by the upload. A read or dry-run check cannot establish
current remote write/ACL permissions; verify those on the next real publish.

The old localStorage library has been replaced by
`website.component.legacy-storage`. It preserves the existing Transit-encoded
key and payload, caches reads, batches writes and handles cross-tab updates.
New persistent component state uses the existing scoped storage system.

Several larger libraries remain because they perform real work:

| Library | Current responsibility / reuse |
| --- | --- |
| Malli + Reitit coercion | Shared component/module/page/provider contracts, app-db sections and HTTP path/query/body coercion. Extend those schemas instead of creating parallel validators. |
| core.async | Existing channel-based application work and sequential lifecycle-test adapters. |
| Transit | Clojure values over HTTP and compatibility with existing stored state. |
| Jsonista / Cheshire | Application JSON and clj-http JSON coercion respectively; both are explicit runtime dependencies with aligned Jackson versions. |
| Optimus + image transforms | Asset bundles, URLs and runtime image transforms. Its Graal/Truffle dependencies are substantial; removing them requires replacing these active asset paths. |
| clj-http | Server-side provider HTTP adapters; use existing bulk plans rather than adding transport clients in views. |
| cljs-time | Existing date calculations/formatting. |
| PostgreSQL JDBC + next.jdbc | Active database provisioning tooling. Reads explicitly retain unqualified lower-case keys; imports retain per-table transactions and heterogeneous row defaults. Schema installation joins a supplied transaction. java.jdbc and HoneySQL are confined to `:experiments`. |
| tools.logging + Telemere + SLF4J bridge | Application logging facade and Telemere sink, including Java libraries using SLF4J 2. Keep the bridge compatible with the resolved SLF4J API. |
| org.clj-commons/pretty / Prone | Pretty exceptions shared with Telemere, and development error pages respectively. |
| Leaflet / react-leaflet | Preserved map functionality. |
| react-markdown, remark-gfm, rehype-raw, syntax highlighter | Existing Markdown rendering and highlighting. |
| xmlhttprequest | Node test transport adapter; a development dependency. |

## JVM logging

Telemere 1.4 is the application logging backend. Existing calls use the upstream
`taoensso.telemere.timbre` migration shim; new structured telemetry can use
`taoensso.telemere` directly. The handler loads `website.log` before services.
Mount retains the existing `logger-timbre` state identity, while `website.log`
owns namespace thresholds, info-level console output, pretty exceptions and
10 MB rotating `log/website.log` output with five uncompressed numbered archives.
File handlers are synchronous and retain the existing rate limits. Service stops retain the sinks for final shutdown diagnostics; reinitialization
replaces/closes the owned handlers. Synchronous writes flush immediately;
process exit releases remaining file descriptors.

`clojure.tools.logging` uses Telemere's adapter; `telemere-slf4j` is the sole
SLF4J 2 provider. Keep Throwables intact through both bridges. Explicit logger
initialization owns the sole default uncaught exception handler; loading `core`
or `log` must not replace it. `org.clj-commons/pretty` supplies the log exception
formatter and REPL hooks. Do not add legacy `io.aviso/pretty` alongside it.

Log lines retain the local `yyMMdd HH:mm:ss.SS` timestamp and pretty printed
arguments/exceptions. Telemere's human-readable layout additionally shows signal
kind/ID and structured data; the presentation is intentionally updated, while
existing namespace patterns retain their exact versus wildcard meanings.

JBoss Logging is required by Undertow, XNIO and JBoss Threads. Its automatic
selection prefers Log4j when available and otherwise falls back to JUL unless
Logback is present. The shared Lein JVM options and Docker command explicitly
select `-Dorg.jboss.logging.provider=slf4j`, routing HTTP-server logs to Telemere
from class initialization, before the application logger is mounted. Include
that option when launching the JAR directly too. A running JVM retains its
selected provider; restart it to pick up dependency or JVM-option changes.

There is no Log4j or Logback backend in the resolved application graph and no
Logback XML configuration. `log4j:WARN No appenders could be found` means a
Log4j backend was selected without any output destination. Check the emitting
JVM's actual classpath and launch options if this appears; do not add a second
logging backend or configure Log4j merely to suppress the warning.
Apache HttpClient 4's transitive `commons-logging` facade remains necessary for
`clj-http`; it is not an additional application backend.

## Upgrade checks

```sh
lein ancient
lein deps :tree
lein classpath
npm outdated
npm audit
npm audit --omit=dev
npm ls --depth=0
npm run build
```

Inspect actual JARs when two different artifact names may supply the same classes:
Maven chooses one version per coordinate but cannot detect every duplicate class.
Do not treat earlier classpath size, duplicate-class or audit counts as current
results after changing dependencies. Record measurements from the final resolved
graph, distinguish default development from packaged runtime, and report artifact
sizes separately from JVM heap use. Check both npm audit scopes; successful
upgrades do not establish a clean security audit.

The final cleanup on 2026-10-06 resolves 174 JARs (90.8 MiB) for default
development and 144 JARs (61.3 MiB) for the production runtime. These are artifact
sizes, not memory measurements. The production graph has no duplicate class
names. Development has four duplicated JSpecify annotation classes supplied by
Closure Compiler and JSpecify; there is no second Closure implementation.

The locked npm audit reports 15 findings across build/runtime dependencies
(6 high, 9 moderate), including 5 moderate runtime findings in the Markdown
conversion chain. npm reports no automatic fixes for the current versions.
Keep this limitation visible during upgrades; replacing the Markdown or build
pipeline requires separate behavior testing.

Follow [testing.md](testing.md), including fresh SSR fixtures and live browser
checks. For an isolated renderer build, use
`bb ssr:fixtures --worker target/path/to/site.js`.
Do not run a competing compilation against a watched build. A running Lein REPL
keeps its original JVM classpath, so use a separate process to validate dependency
changes; restarting it is an explicit development-session decision.

After packaging, start the actual image and exercise a provider-backed route.
The development classpath includes browser libraries and can hide missing runtime
adapters (for example clj-http requires Cheshire for `:as :json`).

Finally run `make docker-prefab` and verify the published dependency-hash image
and compatibility tag. Keep the fallback prefab stage aligned with
`Dockerfile.builder`. Run a final image build to check AOT, documentation and
packaging together. Ordinary source-only changes reuse the prefab.

Supabase's SDK is bundled in the ordinary lazy `:supabase` Shadow module. The
vendored UMD file is preserved for reference and excluded from runtime jars;
server HTML does not include it. Phoenix 0.4.5 emits two npm JSDoc parse notices
for TypeScript-style `@template [T=PresenceState]` comments. These do not change
executable code; configuration audits still require zero CLJS compiler warnings
and recognize only those specific upstream comment notices.

## Sass compiler host

CSS builds use the official `sass-embedded` host, pinned to Dart Sass 1.105.1.
A single asynchronous compiler handles each build's sequential sheets and closes
in `finally`, including failed watch rebuilds. npm selects its native binary for
macOS development and Linux ARM64/AMD64 prefabs; retain optional dependencies
when installing. PostCSS/Autoprefixer, style freshness and atomic publication keep
their existing owners. See [Sass Embedded](https://sass-lang.com/blog/embedded-sass-is-live/).

## API descriptions

`fi.metosin/reitit-openapi` matches Reitit 0.11.0 and generates OpenAPI 3.1 at
`/api/openapi.json`; the existing `/api/api-docs/` Swagger UI loads it by default.
OpenAPI 3.1 is explicit because this released generator defaults to 3.2, while
the bundled UI supports 3.1. Shared Malli parameter/response schemas supply both
descriptions. Keep `reitit-swagger` for the original `/api/swagger.json` Swagger
2 compatibility contract. This migration does not change request coercion or
response formats. Reitit's OpenAPI generator remains upstream alpha.
