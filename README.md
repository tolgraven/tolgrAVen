# tolgraven

The code for my main website at https://tolgraven.se

## Prerequisites

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## Setup and development

Use Java 21 or newer, Leiningen, and Node.js 22. Install the lockfile and build CSS:

```sh
npm ci
npm run build
lein repl
```

The development server runs at http://localhost:4000. Run `npm run dev` in a
second terminal to watch CSS. In the Clojure REPL, `(cljs-repl)` selects the
`:app-dev` browser runtime and watches the `:ssr` Node target. SSR is enabled by
default; configure `:ssr {:enabled true :render-workers 2 :worker "target/ssr/site.js"
:node-binary "node"}` in `dev-config.edn`. See [SSR configuration](doc/blog-ssr.md). `(restart-handler)` reloads the Ring handler after
reloading changed backend namespaces.

Set `SUPABASE_PUBLIC_URL`, `SUPABASE_ANON_KEY` and server-only
`SUPABASE_SERVICE_KEY` in the application environment. Apply the schema and native
operations before starting; see [Supabase provisioning](doc/supabase-provisioning.md).
Firebase is no longer a runtime or build dependency. Local migration exports and
credentials remain ignored and are not needed to run the site.

## Validation

```sh
lein with-profile +test test
lein with-profile +test run -m shadow.cljs.devtools.cli compile supabase-test
lein with-profile +test run -m shadow.cljs.devtools.cli compile app-test
python3 scripts/serve-browser-tests.py
```

Open http://localhost:4002 to run the browser tests. They cover the split route
tree, asynchronous navigation, module initialization, search completions, and
Supabase cache/stream behavior. The server also provides the image fixtures.
After recompiling, use a hard reload to avoid a cached test bundle.

Compile production JavaScript without replacing a running development build:

```sh
lein with-profile -dev,+prod run -m shadow.cljs.devtools.cli release app --config-merge '{:output-dir "target/production-js"}'
```

`lein uberjar` builds the deployable server, production JavaScript, and Codox.
Both browser builds use the same lazy module graph. Pull requests validate CSS;
the deployment job runs only for pushes to `master`.

See [component authoring](doc/components.md), [page rendering](doc/blog-ssr.md),
and [testing](doc/testing.md) for current architecture and verification workflows.

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

Shared data and declaration contracts: [Malli schemas and validation](doc/schemas.md).
