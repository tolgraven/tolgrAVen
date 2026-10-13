# Runtime configuration

Each environment uses `env/<environment>/resources/config.edn`. The development
and test profiles explicitly load their respective file with `-Dconf`. The `prod`
Lein profile overlays `config/prod-local.edn` on its production classpath resource;
the packaged Docker command explicitly loads
`env/prod/resources/config.edn`. Dev/test files are private and ignored by Git and
Docker; production's tracked defaults contain public settings only.

Copy `config/dev.example.edn` and `config/test.example.edn` to their environment
paths on a new checkout/worktree. `config/defaults.example.edn` is the commented
reference, including alternative local, production, offline and integration setups.
It contains placeholders only. Private configs are not copied by Git worktrees.
Old local overrides can be retained in `.local-wip/config-backups/`; that directory
is ignored and is not searched by the config loader.

## Which settings are necessary?

| Setting | When it is needed | Default or normal choice |
| --- | --- | --- |
| `:port` | Every HTTP server | Explicit dev choice 4000; test/prod 3000. The app passes the configured port to the server. |
| `:dev` | Development assets and middleware behavior | Set true in dev, false/omit in production. The Lein profile separately selects the environment adapters. |
| `:host` | Restrict HTTP listening or allow LAN/container access | Undertow uses `:host`, not `:bind`. Local prod uses `127.0.0.1`; packaged prod defaults to `0.0.0.0`. |
| `:https-required` | Production transport/session policy | Production defaults to HTTPS behind a TLS proxy. Only explicit `false` selects HTTP middleware; local prod sets it with loopback listening. |
| `:analytics` | Production sites that want Google Analytics | Optional. No script without `:google-id`, or set `{:enabled false}`. Current prod defaults contain this site's ID; use your own for another site. |
| `:ssr` | Override server-rendering settings | Enabled with two workers and the local Node build by default; see below. |
| `:strapi-url`, `:strapi-read-token` | Read content from an external CMS | Without a CMS URL, public content comes from the local seed. A configured CMS needs its server read token. |
| `:supabase-public-url`, `:supabase-anon-key`, `:supabase-service-key` | Enabled modules using Supabase | No credential defaults. The public key is for browser access; the service key stays on the server. |
| `:openai-api-key` | Requests to the enabled GPT module | No default; `OPENAI_API_KEY` can supply it. |

Analytics is an explicit production choice, not a startup requirement. A site can
run without analytics. A storage-free site's disabled Supabase modules do not
need Supabase settings. A configured CMS is checked during public-content startup,
so an unavailable CMS can prevent startup. Feature selection belongs to
`resources/site.edn` plus CMS overrides, not a parallel list in runtime config;
see [Site capabilities](site-capabilities.md).

`GOOGLE_SENDMAIL_SCRIPT` is an environment-only setting required for the enabled
contact endpoint. It is the deployment ID, not a full URL. Image uploads require
Babashka and the configured codecs; `:image-converter` defaults to
`scripts/media/images.clj` (Docker sets `/app/scripts/media/images.clj`). Legacy
Firebase/OAuth and `:database-url` entries in an old local file are not requirements
for the current Supabase/Strapi app.

## Defaults and overrides

SSR consumers merge these defaults with the `:ssr` map:

```clojure
{:enabled true
 :render-workers 2
 :cache-ttl-ms 3600000
 :worker "target/ssr/site.js"
 :node-binary "node"}
```

Shared-shell refresh defaults to `:shell-refresh-seconds 30`. Production Docker
uses `/app/ssr/site.js`. Disable SSR with `:ssr {:enabled false}`; otherwise build
the renderer before requesting rendered pages. See [SSR configuration](ssr.md).
Omitted `:io-threads` becomes twice the JVM's available processor count. Internal
validation defaults to development only; `:validation-enabled` or
`VALIDATION_ENABLED` overrides that. Public request coercion remains enabled.

Application configuration merges the selected classpath resource, the explicit
`-Dconf` file, Mount arguments, system properties, then environment variables.
The test profile replaces the development resource paths so dev credentials are
not merged into a test run. Environment variables have the highest priority.
For example:

```sh
PORT=4011 lein with-profile prod run
```

This local production command serves **HTTP** at `http://127.0.0.1:4011`.
`config/prod-local.edn` keeps production asset handling but selects HTTP middleware
and the local renderer/image-converter paths. CSRF and request protections remain
active. Packaged production retains HTTPS redirects, HSTS and secure cookies;
its Docker configuration is unchanged. Run `bb prod:build` first; local production uses `target/production/resources`
ahead of shared assets and `target/production/ssr/site.js`, with separate Shadow
build IDs/caches from development watches. Missing/development browser output
fails startup; see [Testing](testing.md).

The Supabase platform adapter reads REST/JDBC settings and its TLS policy from
`website.config/env`, consolidated by cprop with the precedence above. For example,
`SUPABASE_PUBLIC_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_DATABASE_URL` and
`SUPABASE_INSECURE_TLS` map to the corresponding kebab-case EDN keys. JDBC can
also use `:postgres-hostname`, `:postgres-db`, `:postgres-user`,
`:postgres-password` and optional `:postgres-port` / `:supabase-db-sslmode`;
existing Supabase, Postgres and PG aliases remain supported. The adapter declares
aliases in priority order, defaults, required JDBC components and TLS conversion
in `connection-config`; one resolver maps the consolidated values to connection
keys. Missing-config diagnostics derive keyword aliases from those declarations
(`:config-keys` in exceptions, `:expected-config` in the missing database summary).
Cprop maps shell names to those keywords; the adapter does not duplicate shell
variable names or read the process environment again.
The Supabase provisioning CLI starts only the mounted config state; its `doctor`
command reports those same resolved settings without credentials.
Browser settings support `SUPABASE_ANON_KEY` (or `SUPABASE_PUBLISHABLE_KEY`).
CMS runtime reads prefer `STRAPI_URL` and
`STRAPI_READ_TOKEN`; the build/provisioning config resolver uses these environment
variables, so export them when enabling build-time CMS overrides. Nested options
such as `:analytics` and `:ssr` are most clearly expressed as EDN maps.

`:nrepl-port` is optional for `lein run`: omitting it prevents the application
from starting an nREPL server; `:nrepl-bind` chooses its bind address. `lein repl`
has its own port from `project.clj` (7000 in dev). Give a second app a different
HTTP/nREPL port when both run together. A running JVM keeps its original `-Dconf`
property; restart it after relocating config files to pick up the updated profile.

Git ignores do not protect local jar packaging. Development jar exclusions remove
private `config.edn` resources; production's non-secret defaults remain packageable.
