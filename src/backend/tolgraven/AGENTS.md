# Server adapters

Keep this guide current. See `doc/ssr.md`, `doc/strapi-content.md`, and
`doc/site-provisioning.md` for operating and configuration details.

- Feature-owned server adapters live under `modules/<feature>/`; shared platform
  adapters retain their `content/`, `supabase/`, and `ssr/` ownership.
  Landing routes come from `modules/home/pages.cljc`; their code/CSS assets use
  the same lazy `:home` bundle as SPA navigation.
- Use shared CLJC data contracts and page declarations. Generic SSR must not grow
  page-specific SQL/REST predicates or duplicate component markup.
- Public snapshot acquisition owns a bounded Supabase connection pool across
  plan levels. Joined tasks inherit its binding; per-request headers and TLS
  policy remain in the transport, and success/failure closes the pool.
- Source acquisition runs concurrently under bounded limits; identical requests
  share an in-flight task. Node workers perform isolated React rendering only.
- The Node response pairs HTML with its module/export inventory. Keep that
  inventory in the matching snapshot/cache entry so selective hydration knows
  which exports rendered real SSR views.
- Capture one renderer build for each cache transaction; an in-flight build
  change must leave its result stale. Cache public HTML with its exact data snapshot.
  Never cache a request's CSRF token or personalized shell as a shared response.
  Errors cannot validate old cache entries.
  Successful page snapshots are fresh for `:ssr :cache-ttl-ms` (one hour by
  default; zero always revalidates). Expired/build-changed entries stream the shell
  while revalidating; only fresh entries bypass it.
  Presentation query variants may reuse fresh data for the same path/selection,
  preserving its original timestamp, but must render their own paired HTML/state.
- Optimus fingerprints the shell and each declared module stylesheet independently;
  shared styles keep one URL across consumer modules. A separate deferred icon
  bundle uses one Sass-compressed selector/font-face sheet; Optimus fingerprints
  its fonts and rewrites CSS references. Development-only inspector
  rules use a separate unbundled sheet; normal and error documents share the shell
  stylesheet policy. Emit
  route dependency styles and User control styles in the first head. Home owns
  its static Markdown styles without depending on Markdown code. Do not add the
  Link Preview popup sheet to the initial head; its static container rule belongs
  to Markdown. Respect module `:ssr-styles :deferred` without removing loader URLs.
  Inline small optimized sheets
  within a 16 KiB document budget; publish all URLs in `#module-styles`;
  local return documents carry their saved modules' styles before first paint.
  Strip a leading UTF-8 marker when inlining CSS so its first selector remains valid.
- Keep SDK and app scripts ordered and deferred, including local-return templates. Emit no analytics metadata,
  queue or scripts; the browser initializes it only after hydration and page readiness.
  SSR hydration preloads follow only the route’s Shadow dependency graph; other
  module exports acquire code on activation. Preloads use low fetch priority in
  both HTTP and HTML hints so they share bandwidth behind the first-paint
  stylesheet and fonts.
  Lazy chunks use `as=fetch` with anonymous CORS to match Shadow's XHR loader;
  the main script uses `as=script`.
  Image preloads must use the same responsive candidates as rendered pictures.
- Image/video catalog macros track their EDN resources through Shadow during
  CLJS compilation; JVM runtime reads do not require the compiler dependency.
- Production Shadow chunks use content-hashed names from `manifest.edn`; the
  Optimus main bundle resolves that manifest too. Only successful fingerprinted
  chunk responses receive immutable caching; plain names and errors must not.
- Streaming uses Hiccup document parts and the existing flush-aware Ring body;
  do not split serialized pages by marker strings. Headers cannot change after flush.
  Compress that body directly with sync-flush gzip when accepted; do not convert
  it to the generic middleware's buffered InputStream.
- A return cookie alone never bypasses network SSR. Only the local worker's
  explicit `X-Page-Render: state` fallback requests browser state restoration.
- Validate/coerce requests at the Ring boundary; handlers consume parsed parameters.
  Authentication/authorization and input protection remain active in production.
- Secrets remain in server adapters/configuration. Runtime public settings must
  expose only public origins/keys and allowed options.
- ImageMagick pixel-cache files stay inside the request temporary directory so
  failure/timeout cleanup also removes them. Avatar uploads normalize/validate PNG
  before bounded `media/image` conversion.
  Publish versioned PNG/WebP/AVIF to Supabase Storage before updating the profile;
  its mounted object backend owns persistence, never the web container filesystem.
- Do not stop a user's REPL or rebuild a watched Shadow target with another process.
- `build/audit.clj` uses isolated output and source maps to produce the optimized
  per-source report alongside chunk sizes; keep the normal compatibility hooks
  and optional-main boundary checks when changing this workflow. Browser audits
  also reject Markdown-clj and every Malli implementation namespace in main.
  Release browser, renderer and return-worker graphs reject 10x/re-frisk/legacy
  inspectors through `build/policy.clj`; stale analysis cache files are not graph membership.

- Reitit uses shared Malli parameter schemas. Internal/response validation follows
  runtime configuration; request coercion remains active. Coercion error handlers
  must not log full exception data or return request/response values.

- `ssr/contract_schema.cljc` is the JVM/Node protocol boundary. Hydration and
  local-return adapters live in frontend CLJS; module-owned snapshot conversion
  is shared by the Node renderer and browser, not required by the JVM server.

- Network SSR snapshots carry `:route-parameters`, coerced by the server router,
  alongside raw `:query-params`. The browser trusts these only for its exact initial
  HTML/state pair; subsequent navigation uses the deferred browser adapter.

- Link Preview owns `/api/link-preview`: bounded public HTML reads, checked DNS at
  connection time and every redirect, short failure caching and shared in-flight
  reads. Score candidate subtrees in one traversal; failure completion may update
  only its own still-cached promise. Emit text blocks/image metadata and frame policy,
  never remote HTML.

- oEmbed derives native player URLs from provider HTML with the shared endpoint
  allowlist. Do not trust provider-supplied player metadata or return arbitrary
  URLs as origin-enabled players. Other markup requires an opaque browser sandbox.
  The bounded cache shares in-flight requests, keeps success for fifteen minutes
  and failure for one minute, and retains only consumed provider fields.

- Development source documentation is acquired through `tolgraven.dev.source`,
  resolved only when the server development flag is true. Catalog allowed source
  roots, reject symlinks/traversal/oversized files and recheck canonical ownership.
  Never expose configuration files or accept arbitrary filesystem paths.
