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
  its JVM version. Node 22 or newer is required.
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

## Optional and retained libraries

The development CIDER Lein plugin injects its middleware automatically. The
REPL handler explicitly lists Piggieback and Shadow to satisfy their middleware
ordering dependencies. CIDER 0.57.0 also constructs its own handler when its
namespace loads, and Shadow starts a separate nREPL server from `user/start`;
both can still emit missing-Piggieback warnings with nREPL 1.8.0. That version
automatically adds the missing middleware after warning.

Default development starts the custom console and keeps re-frame tracing
available to re-frame-pair. 10x and re-frisk are opt-in with `:legacy-debug`;
see [the debugging guide](re-frame-pair.md). This avoids loading two additional
inspectors into every development page.

`:experiments` retains HoneySQL and optional diff/Ring middleware dependencies
for preserved prototypes. Its `experiments/clj` source path keeps the SQL prototype
out of normal application compilation. Enable the profile before working on it.
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
`tolgraven.component.legacy-storage`. It preserves the existing Transit-encoded
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
| PostgreSQL JDBC + clojure.java.jdbc | Active database provisioning tooling. HoneySQL is confined to `:experiments`. |
| tools.logging + Timbre + SLF4J bridge | Application logging facade and Timbre sink, including Java libraries using SLF4J 2. Keep the bridge compatible with the resolved SLF4J API. |
| io.aviso/pretty / Prone | Application exception/log formatting and development error pages respectively. |
| Leaflet / react-leaflet | Preserved map functionality. |
| react-markdown, remark-gfm, rehype-raw, syntax highlighter | Existing Markdown rendering and highlighting. |
| xmlhttprequest | Node test transport adapter; a development dependency. |

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
`python3 scripts/test-blog-ssr.py --worker target/path/to/site.js`.
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
