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
- The Maven repository uses ordinary HTTPS; the unused S3 wagon and shell
  plugins are removed. This also avoids fetching their AWS SDK/plugin graphs.
- CSS tools are local development dependencies. `npm run init` installs from the
  lockfile; it does not install global tools.

## Optional and retained libraries

Default development starts the custom console and keeps re-frame tracing
available to re-frame-pair. 10x and re-frisk are opt-in with `:legacy-debug`;
see [the debugging guide](re-frame-pair.md). This avoids loading two additional
inspectors into every development page.

`:experiments` retains dependencies for preserved experiments, including
CodeMirror, React Player and optional Ring middleware. Enable that profile before
restoring those experiments; their source has not been deleted.

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
| PostgreSQL JDBC + HoneySQL | Database tooling and preserved SQL functionality. |
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
The October 2026 cleanup removed simultaneous shaded and unshaded Closure
compilers (2,340 duplicate class names). The only remaining duplicate class
names in the inspected graph are four JSpecify annotations bundled with Closure
and also supplied by the annotation artifact; Closure is build-only.
The default development classpath decreased from 240 JARs / 107.8 MiB
to 183 JARs / 92.9 MiB; these are artifact sizes, not JVM heap measurements.
The packaged runtime dependency classpath contains 145 JARs / 61.3 MiB and
excludes Shadow, the ClojureScript compiler and Closure Compiler.
The npm lockfile decreased from 264 to 243 packages. `npm audit` still reports
findings in retained Markdown and development-tool chains; dependency upgrades
are not a claim of a clean security audit.

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
