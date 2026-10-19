# tolgraven

The code for my main website at https://tolgraven.se

## Prerequisites

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## Setup and development

Use Java 21 or newer, Leiningen, Babashka 1.13.225 or newer, and Node.js 22.12 or newer.
Python 3 with `venv`/pip support is also required. `lein repl` automatically
prepares pinned icon tools in the ignored `.local-wip/python` environment before
starting the application. Run `bb dev:setup` to prepare or repair it explicitly;
no shell activation or global pip installation is needed. The dependency prefab
already supplies its runtime tools. See [icon font setup](doc/styles.md#icon-fonts).
Install the lockfile and build CSS:

```sh
cp config/dev.example.edn env/dev/resources/config.edn
cp config/test.example.edn env/test/resources/config.edn
npm ci
npm run build
make hooks
lein repl
```

`lein repl` uses [neorepl](https://github.com/nrepl/neorepl), with syntax-coloured
input and pretty-printed values, completion, documentation and CIDER tooling.
The client runs in Babashka; its release is pinned in `config/neorepl.edn` and is
downloaded on first use. `NO_COLOR` disables colours. Ctrl-C interrupts an
evaluation; Ctrl-D or `:repl/quit` exits and stops this development server.
`(cljs-repl)` switches this same session to Shadow; `:cljs/quit` returns to Clojure.
Use `lein repl :port 7001` for another nREPL port, `lein repl :headless` for an
editor-owned client, or `lein repl :connect localhost:7000` to attach without
starting or stopping that server. `LEIN_REPL_HOST`, `LEIN_REPL_PORT` and
`LEIN_REPL_ACK_PORT` are supported. `lein repl-classic` retains Leiningen's
original REPL-y task and its full transport/socket/profile configuration.

The development server checks CSS before opening HTTP and rebuilds stale or missing
sheets with the locked tools and owns one supervised Sass/PostCSS watcher.
Fresh output skips compilation. It runs at http://localhost:4000; a separate
`npm run dev` is optional for CSS-only work. Development browser compilation and
HTML/CSS/chunk requests recheck freshness, so failed builds return 503 instead of
serving stale sheets. `(stop)` and JVM exit stop only the REPL-owned watcher. The Clojure REPL becomes available before the
initial Shadow compilations finish. An application startup failure (including an
occupied HTTP port) is logged with its cause while the REPL and native Shadow
watches still start; fix the cause and call `(start)` to retry HTTP. CSS failures
remain a startup barrier so stale sheets cannot be served. After a short deferral, the standalone
`:document` bootstrap is released first with its own optimized runtime. Documents
and bootstrap requests wait for that release; unrelated assets and APIs stay
available. Native watches then start in order: `:return-worker`, `:ssr` when
enabled, then `:app-dev`. The document target has no HMR/browser REPL; changes
require another standalone release. Development JavaScript uses `no-store` so
browser caches cannot retain a previous Shadow process's connection identity.
`(cljs-repl)` waits
for those watches and selects the browser runtime in your session. SSR is enabled by
default; configure `:ssr {:enabled true :render-workers 2 :worker "target/ssr/site.js"
:node-binary "node"}` in `env/dev/resources/config.edn`. See [SSR configuration](doc/ssr.md). `(restart-handler)` reloads the Ring handler after
reloading changed backend namespaces.

Set `SUPABASE_PUBLIC_URL`, `SUPABASE_ANON_KEY` and server-only
`SUPABASE_SERVICE_KEY` in the application environment. Apply the schema and native
operations before starting; see [Supabase provisioning](doc/supabase-provisioning.md).
Firebase is no longer a runtime or build dependency. Local migration exports and
credentials remain ignored and are not needed to run the site.

See [Runtime configuration](doc/configuration.md) for required keys, defaults and
production analytics/CMS/storage settings.

## Validation

```sh
lein with-profile +test test
lein with-profile +test run -m shadow.cljs.devtools.cli compile supabase-test
lein with-profile +test run -m shadow.cljs.devtools.cli compile app-test
bb test:browser
```

Open http://localhost:4002 to run the browser tests. They cover the split route
tree, asynchronous navigation, module initialization, search completions, and
Supabase cache/stream behavior. The server also provides the image fixtures.
After recompiling, use a hard reload to avoid a cached test bundle.

Build and run local production without replacing watched development assets:

```sh
bb prod:build
PORT=4011 lein with-profile prod run
```

Open `http://127.0.0.1:4011/`. The local releases use separate Shadow build IDs and
`target/production` output; production startup rejects development browser bundles.
The packaged server retains its HTTPS proxy policy. See [testing](doc/testing.md)
for agent CPU controls, isolated build output and live provider checks.

`lein uberjar` builds the deployable server, production JavaScript, and Codox.
Both browser builds use the same lazy module graph. Pull requests validate CSS;
the deployment job runs only for pushes to `master`.

See [component authoring](doc/components.md), [page rendering](doc/ssr.md),
and [testing](doc/testing.md) for current architecture and verification workflows.
For a detailed comparison with classic Reagent and an assessment of this
project's choices, read [Reagent 2 and React 19](doc/reagent-2-react-19-guide.md).
The [native Reagent implementation guide](features/reagent-native-refactor.md)
records current ownership contracts and the adapters that need deliberate review.

## License

Copyright © 2020-2026 Joen Tolgraven

<img width="1033" height="1014" alt="image" src="https://github.com/user-attachments/assets/df07bdb0-0af1-4dab-a920-652882f08323" />


## PR staging deployments

The GitHub workflow validates CSS and then forwards PR opening, reopening, and
commit updates to Coolify's staging GitHub webhook. Set the repository Actions
secret `COOLIFY_WEBHOOK_SECRET` to the staging application's **GitHub Webhook
Secret** under Coolify **Configuration → Webhooks**. Enable **Preview Deployments**
for that application, with repository `tolgraven/tolgrAVen` and base branch `master`.
The workflow signs the original PR payload and checks that Coolify queues it.
Repository PRs deploy automatically; fork PRs run validation without the staging job.

See [test boundaries and workflow verification](doc/testing.md) for the full browser suite, unmocked live application checks, and database/infrastructure tests.

Source organization: [ownership and platform boundaries](doc/source-layout.md).

Shared data and declaration contracts: [Malli schemas and validation](doc/schemas.md).

Build, media and test commands are listed by `bb tasks`; see [tool ownership](doc/tooling.md) and [local configuration](config/README.md).
