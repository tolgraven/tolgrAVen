# Middleware and Optimus audit

The application still needs asset URL resolution, independent module stylesheet
fingerprints, correct cache headers, static resource delivery, content negotiation
for APIs, and a flush-aware streaming body. Several current implementations repeat
work already done by the build or operate at the wrong boundary. Keep the useful
contracts while simplifying their implementation; do not remove Optimus without
first replacing its stylesheet/font URL manifest and SSR consumers.

This report audits application source `086ad362` and the resolved Optimus
2026.05.27, optimus-img-transform 0.3.1, Ring core 1.15.5, Ring defaults 0.7.1,
ring-gzip-middleware 0.1.4, ring-partial-content 2.1.0, Muuntaja 0.6.12 and Reitit
0.11.0 source. Recommendations below are proposals, not implemented changes.
The reproducer changes only files inside a disposable CPU-controlled snapshot.
No production deployment or running user REPL is modified.

## Priority recommendations

1. Separate externally authenticated API endpoints from cookie/form CSRF policy.
   A bearer-authenticated Strapi webhook does not carry the browser's CSRF token.
   Keep its existing constant-time secret validation; retain CSRF for browser
   cookie/form flows. Do not disable CSRF for the entire site to unblock one route.
2. Replace the generic gzip policy with response MIME/encoding based selection.
   Add `Vary: Accept-Encoding` consistently and honor explicit gzip refusal.
   Keep the existing streaming adapter's synchronous flushes. Preserve API encoding
   before compression, and arrange conditional responses before starting compression work.
3. Replace the legacy partial-content implementation with a maintained, verified
   range implementation or delegate media delivery to the static server/proxy.
   Preserve whole and partial immutable-media caching. Cover suffix/open ranges,
   unsatisfiable ranges, `If-Range`, HEAD and stream closure before rollout.
4. Remove runtime JS minification and JPEG/PNG recompression after build ownership
   is explicit. Shadow already compiles application/bootstrap JS; Sass/PostCSS
   already produce compressed CSS; the media generator owns modern formats and
   responsive renditions. These transformations should run once during build or
   upload, rather than again at web-handler construction.
5. Give multipart decoding one owner and enforce upload size/count limits there.
   The global parser, Ring defaults parser, and endpoint Reitit parser currently
   read the same request body. The tested upload **succeeded** while invoking all
   three parsers: existing fields survive merging. This is redundant work, not a
   demonstrated upload failure. Preserve endpoint coercion and browser CSRF
   protection when choosing the owner.
6. Replace broad asset discovery and repeated middleware with a build manifest and
   one static-delivery boundary. Keep Optimus's independent style bundles and CSS
   reference rewriting until equivalent build outputs are proven.

## What actually happens

`handler/app` puts `concurrent/wrap-async` outside `middleware/wrap-base`.
The async path dispatches the complete synchronous stack onto a Java 21 virtual
thread, with 64 admitted requests. It is not a second asynchronous implementation
of each individual middleware. The existing upstream semaphore limits acquisition
separately. Keep this boundary: moving gzip's legacy `future` onto more threads is
not a substitute for reducing unnecessary compression or decoding.

Requests enter these wrappers in this order; responses unwind in reverse:

```text
wrap-internal-error
  wrap-formats (global Muuntaja negotiation, decoding, parameter merge)
    wrap-multipart-params (global, default limits)
      wrap-media-cache
        wrap-partial-content
          wrap-not-modified
            wrap-gzip-content-aware
              wrap-module-cache
                wrap-content-type (AVIF addition)
                  Optimus
                    wrap-resource (prefer handler)
                      Ring defaults
                        environment middleware
                          routes and endpoint middleware
```

Optimus short-circuits this inner stack for every registered asset URI, including
its plain/outdated URI. Consequently Optimus assets skip Ring defaults' sessions,
CSRF, resource wrapper, HTTPS redirect and security headers. Unregistered static
files normally reach Ring defaults' resource handler instead of the route.
Those two static paths do not have equivalent policy. Static assets still traverse
the outer global Muuntaja/multipart/compression/range wrappers.

Production and staging both freeze/optimize assets once when the handler is
constructed. Staging uses ordinary `site-defaults`, allowing local HTTP and omitting
production's secure-cookie, proxy, SSL-redirect and HSTS configuration. Production
uses `secure-site-defaults` plus proxy interpretation. Development skips all asset
optimizations. The development environment additionally reloads source namespaces
and renders Prone errors for routed requests; Optimus-served files bypass it.

## Every asset-loading and optimization step

| Step | Current behavior and benefit | Cost or mismatch | Recommendation |
| --- | --- | --- | --- |
| Shell `styles.css` | Loads the precompressed shell sheet as its own bundle. | Runtime text loading and hashing repeat some build work. | Retain the independent shell contract; eventually produce its URL in the build manifest. |
| Module stylesheet bundles | One bundle per declared stylesheet, shared paths deduplicated. | Loading all styles on the server is startup work, not an eager browser download. | Retain. Do not combine into one global sheet or restore eager client acquisition. |
| `icons.css` and CSS references | Loads only declared icon CSS and recursively discovers fonts/images referenced by CSS. | Recursive discovery and URL rewriting currently happen at startup. | Retain reference rewriting and byte-preserving font publication; move to build once the manifest replaces it. |
| Main JS | Resolves Shadow's content-hashed main filename using its manifest, then adds a second Optimus bundle URL. | Single-file concatenation/double URL fingerprint ownership adds no code splitting benefit. | Prefer Shadow's own hashed URL eventually; preserve preload request kind and source map behavior during migration. |
| Document head/entrance JS | Independent generated bootstrap bundles. Their parser-blocking/deferred placement remains owned by layout. | Another pass through JS minification and fingerprinting. | Retain ordering and independent URLs; Shadow should own compilation/minification, with build-generated hashes. |
| Regex image/media inventory | Includes all matching local raster/SVG/GIF and MP4/WebM files. | Whole classpath traversal per regex; every file is later hashed even when no Optimus link consumer uses it. | Keep CSS-referenced assets; use the responsive catalog/build manifest for other immutable media. URLs in the rendered picture/catalog are commonly plain URLs already covered by media caching. |
| Sitemap/robots | Loaded as binary assets and given fingerprinted variants. | Crawlers use the stable plain URLs; hashed variants have little practical benefit. | Serve the conventional URLs directly with an explicit suitable cache policy; preserve current content. |
| Duplicate guard | Rejects conflicting equal-path/bundle definitions and collapses exact duplicate definitions. | Small compared with discovery/transformations. | Retain equivalent validation at build time. |
| `module-source-maps` | Makes a relative Shadow map URL absolute before relocating main into `/bundles/`. | Necessary only because JS is relocated. | Retain while relocating; remove only once main uses its original Shadow URL and map resolution is checked. |
| `minify-js-assets` | Initializes Uglify and Babel through a javax.script engine, then minifies eligible files. | Engine starts even with zero JS or exclusively already-minified JS. Shadow's output is already optimized; the short document head is redundantly minified. | Remove from runtime once every registered JS owner builds/minifies its output. A temporary explicit eligibility gate avoids engine startup but is less complete than build ownership. |
| Disabled `minify-css-assets` | Not executed. | Current CSS is Sass-compressed and PostCSS-processed already. | Leave disabled; do not add another runtime CSS minifier. Preserve the old commented experiment until separately authorized to remove it. |
| `inline-css-imports` | Replaces local CSS imports, updates reference inventory, propagates dependent last-modified times. | Current built CSS has no imports. Future imports could quietly absorb another deferred feature's CSS. | Replace with a build check prohibiting cross-owner runtime imports; keep required reference/mtime handling separately. It does **not** concatenate all module sheets merely because they were loaded. |
| `concatenate-bundles` | Produces bundle entries while retaining original files marked `:bundled`. | All current declared bundles are single-file wrappers. No current HTTP request count reduction from concatenation itself. | Retain as the current bundle-manifest adapter, then remove with a manifest migration; never concatenate unrelated lazy owners. |
| `transform-images` | JPEG/PNG matching paths are recompressed at quality 0.8, progressive output, unchanged logical path. Caches transformed files in process temporary storage using source mtime/options. | Decodes/reencodes icons and photos alike at startup, despite build/upload media ownership. Codec work returns after each fresh container and transformed bytes may differ from original/catalog assumptions. | Move to image build/upload conversion; preserve selected legacy fallback output explicitly before removing runtime transforms. Do not reencode modern AVIF/WebP. |
| `add-cache-busted-expires-headers` | Makes a 12-hex content-dependent URL path, rewrites CSS references, retains plain entries as `:outdated`, sets **ten-year** Cache-Control/Expires on fingerprinted entries. | Reads/hashes every selected binary resource; current implementation hashes a UTF-8-decoded string rather than raw bytes. Plain entries do not inherit those headers. | Retain fingerprinted style/font policy and rewriting. This step enables long caching; it does not disable media caching. For media with immutable filenames the existing one-year plain-media policy is sufficient. Prefer raw-byte hashing in any replacement build manifest. |
| `add-last-modified-headers` | Adds source-derived validators to both plain and hashed entries. | Validators are mostly redundant for fresh immutable fingerprints, but useful on plain resources. | Retain conditional validation for mutable/plain resources; do not remove the outer conditional wrapper as “broken” simply because Optimus changes filenames. |

Optimus eagerly reads its Uglify/Babel sources on namespace import. Its POM directly
pins `org.graalvm.js/js` and `js-scriptengine` 19.3.0; these bring Truffle. The
application does not need to run on the GraalVM distribution for that embedded
engine to be selected. `optimus.js/default-engines` prefers `graal.js`. This is the
concrete project path behind the reported Truffle `sun.misc.Unsafe` warning.
Ordinary development selects `optimizations/none`; these transformation costs
are handler startup costs in non-dev mode, not an explanation for sustained
CPU use during ordinary development requests. Removing the runtime minifier call
prevents this engine initialization; removing
its Maven graph is a separate dependency change requiring a refreshed prefab and
verification of any remaining Optimus engine consumers.

### Asset serving strategies

| Strategy | Behavior | Recommendation |
| --- | --- | --- |
| Frozen (production/staging) | Realizes optimized assets/path map once. Requests perform a map lookup; there is no per-request image conversion/minification. | Keep immutable serving semantics. Move heavy initialization to build, not to request-time lazy optimization. |
| Live with TTL (development) | Reloads inventory every two seconds when requested; rebuilds the path map on every request. | Restrict discovery to owned asset roots or reuse a manifest. The generated-image expansion makes repeated whole-inventory reads more expensive. |
| Live autorefresh | Eager inventory, broad `resources` filesystem watcher, reload on every event; no watcher handle/cleanup exposed by this wrapper. | If used, watch the actual owned build outputs, coalesce changes and own cleanup. Do not watch unrelated CMS/media/temp writes. |
| Current selector | Tests `(io/resource "/js/compiled/out/main.js")` once with `defonce`. The path has both a leading slash and lacks `public/`. | Fix the resource lookup and decide strategy at handler construction, when watched output exists. The current probe cannot select the intended autorefresh path. |

## Every wrap-base action

| Action | Assessment | Concrete next step |
| --- | --- | --- |
| Environment middleware | Useful dev reload/error diagnostics; production is identity. Source roots are current. | Retain in dev only. A watched source reload is distinct from the asset watcher. |
| `wrap-defaults` | Supplies cookies/session/CSRF, nested/keyword/urlencoded/multipart parameters, static resources, MIME/charset/length/conditional handling, X-Frame-Options/nosniff, websocket keepalive; prod also proxy/HTTPS/HSTS. | Configure deliberately rather than layering defaults underneath duplicate explicit wrappers. Preserve cookie/CSRF needs and the return cookie parser. Auth is bearer-based; server sessions are not Supabase authentication. Flash has no active application consumer found. |
| Static resources inside defaults | Serves existing public files before routing. | Choose one static owner with correct MIME, cache, security and range policy; do not retain three overlapping resource paths without a purpose. |
| Explicit `wrap-resource` | Handler-first fallback runs only after a 404 from the inner stack. Defaults already serves static files. Reitit's fallback resource handler supplies a third path. | Remove redundant fallback only after source/jar static tests and reserved routes (return worker, return template, API) prove precedence. This wrapper does not solve compression by itself. |
| `wrap-optimus` | Resolves assets/bundles for SSR and serves them. | Retain needed manifest/URL contracts; simplify transformations and narrow asset ownership first. |
| Explicit content type | Outer placement gives Optimus assets a MIME type; AVIF override fills the installed table gap. | Retain equivalent AVIF/WebP/video/font MIME handling at the single static boundary. Inner default MIME handling cannot replace it while Optimus short-circuits defaults. |
| Module cache | One-year immutable only for successful typed, hashed Shadow JS in non-dev mode. | Retain hashed-only rules; do not long-cache plain watch names, missing chunks or worker registration endpoint. |
| Gzip wrapper | Compresses using request `sec-fetch-dest` as image/video exclusion, otherwise delegates to old generic gzip and the custom streaming adapter. | Replace with response-type policy; request headers are optional and URL fetch/preloads can have other destinations. Skip already compressed image/video/WOFF2 bytes, permit useful SVG/text compression, add Vary for both compressed and uncompressed variants. Preserve native streaming flushes. |
| Outer not-modified | Evaluates Optimus validators, unlike defaults' inner copy. | Retain one conditional owner outside static serving but inside encoding/compression work where practical. Existing ordering can start a gzip future before deciding 304. |
| Partial content | Adds byte ranges only with explicit Content-Length and sliceable body. Ordinary Optimus InputStreams have no length. Legacy algorithm misinterprets suffixes and lacks proper unsatisfiable/If-Range handling. | Replace/delegate and test actual files, jar resources and browser video, rather than assuming installing the wrapper proves playback compatibility. |
| Media cache | Keeps existing Optimus policy; one-year public immutable for successful image/video `/img` and `/media` 200/206 outside dev. | Retain the user's immutable-filename contract. Remote Supabase media is served by Storage and needs that server's headers separately. Never extend this to errors, documents or API JSON. |
| Explicit multipart | Runs before defaults and route coercion, with no file/count cap. | Give parsing one owner with the existing 5 MiB policy enforced during intake, before full temporary-file consumption. Preserve filename/type/schema and temporary-file cleanup. |
| Global formats | Negotiates/decodes requests and encodes map bodies for all paths, except `:websocket?`. Reitit API middleware does this already. | Keep formats on APIs with typed/coerced inputs and response encoding; remove global duplication only after verifying the return-template/raw-json and HTML routes. Put format exceptions under the proper 400 handler. |
| Internal error | Catches handler construction/execution exceptions and renders an error page. Body-write failures after streaming headers flush cannot become a new HTTP status. | Retain an outer fallback, preserve known statuses where applicable and use a generic public error message. Make sure the error renderer itself can run without a request's ended dynamic bindings. |
| Commented/unused wrappers/imports | Flash/CSP/CORS/rate-limit/log experiments are not active. Undertow session wrapper is imported but not called. | Do not treat comments/imports as current behavior or remove preserved experiments during this audit. Make any later cleanup explicit. |

`inline-css-imports` does not undermine lazy module acquisition today: there are no
remaining CSS imports to inline. Module stylesheet entries stay independent and
layout publishes their actual optimized URLs in `#module-styles`. Small initial
route sheets use the 16 KiB HTML inline budget; deferred popup CSS still waits for
its owner. Any replacement must preserve this policy and start CSS alongside the
matching module's JavaScript request.

## Reproducing the observations

Run in this isolated audit checkout through the shared resource controller:

```sh
/Users/tol/.local/bin/tolgraven-cpu run -- sh -c \
  'npm run build && lein with-profile +project/test run -m clojure.main scripts/audit/middleware.clj'
```

The script refuses fixture generation outside the controller's `/workspace`
Docker snapshot. It builds real repository CSS, loads the real media inventory,
and uses three explicitly identified disposable JavaScript fixtures instead of
claiming to benchmark a compiled application. It forces each actual optimization
stage, records its wall time, then reuses that asset result while exercising the
actual `wrap-base` stack. Its endpoint fixtures reuse the real service-route
middleware declaration, including formatting and multipart coercion. Its JDK
loopback HTTP server calls the real CMS secret validation; only intake/public URL
and error rendering boundaries are replaced.
No CMS, Storage, database or network write outside loopback occurs.

Results are written to the snapshot's `target/middleware-audit.json`; intermediate
pipeline data goes to `target/middleware-audit-pipeline.json`. These are
investigation samples, not deployed latency or browser/LCP benchmarks. Record the
one-CPU, 2 GiB container and 1 GiB heap alongside any repeated measurements. The
large whole-pipeline run does not establish how an uberjar's ZIP lookup behaves:
installed Optimus opens a ZipFile per mtime lookup without explicit close and
scans entries; that path merits a packaged-artifact check during a manifest move.

### Observed sample

The complete successful run used Java 21.0.12.1, one CPU, 2 GiB container memory,
about 990 MiB effective maximum JVM heap, and Linux VM load average 1.35. The source
inventory produced 575 loaded entries, 571 unique entries and 1,198 optimized
entries. There were 25 active CSS files, **zero CSS imports**, and three fixture JS
files, two already recognized as minified. The generated 26th development-only
CSS entry was not part of the optimized module inventory.

| Stage | Fully realized wall time |
| --- | ---: |
| Discover/load assets | 771 ms |
| Deduplicate | 1.6 ms |
| Source-map URL adaptation | 0.5 ms |
| JS engine/minification | 4,386 ms |
| CSS import/reference processing | 4.4 ms |
| Bundle creation | 0.9 ms |
| JPEG/PNG transformation | 4,125 ms |
| Fingerprint/reference rewriting | 2,099 ms |
| Last-modified headers | 4.0 ms |

These stages total about 11.4 seconds. A separate completed pipeline sample had
about 8.0 seconds of stage work. Dependency retrieval, JVM startup, CSS compilation
and request probes are outside those stage totals. This is evidence of redundant
startup work; it does not establish a production speedup or browser LCP change.

| Probe through the current stack | Observed result | Implication |
| --- | --- | --- |
| Loopback Strapi POST with bearer only | 403, handler not reached | Current global CSRF prevents normal external webhook delivery. |
| Same disposable secret with browser safe header/cookie | 200, `{"accepted":true}` | CMS auth/handler works after the outer browser gate is satisfied. The configured safe header bypasses CSRF token validation when nonempty. |
| Loopback multipart endpoint | 200; three parser calls, two seeing existing params | Remove duplication deliberately; do not claim this reproduced a broken upload. |
| Ordinary text with gzip | 900 bytes became 37 bytes; no Vary | Compression helps, but response variants are not declared for caches. |
| `*;q=1, gzip;q=0` | Still gzip | Legacy negotiation violates explicit gzip rejection. |
| AVIF without Fetch Metadata header | 15,156 bytes became gzip 14,299 bytes; no Vary | Codec bytes depend on an optional request header. This particular image shrank; there is no basis to claim all image gzip inflates bytes. It still causes extra CPU/representation handling. |
| Same AVIF with `sec-fetch-dest: image` | Original 15,156 bytes | Current behavior differs despite identical asset and accepted encoding. |
| Suffix `bytes=-3` on ten-byte video body | 206, range `0-3/10`, body `0123` | Correct suffix is `7-9/10`, body `789`. |
| Unsatisfiable `bytes=20-30` | 206, range `20-9/10`, Content-Length `-10` | Must be a valid unsatisfiable response, normally 416. |
| Ordinary `bytes=2-5` | 206, body `2345`, length 4 | Basic bounded range works; that alone cannot prove video range compatibility. |
| Optimus binary range | 200, complete file, no Content-Length | Range wrapper cannot slice ordinary Optimus InputStream delivery. |
| Optimus HEAD and POST | Each returned the full asset stream at the Ring boundary | Static method/body semantics differ from the ordinary resource wrapper. Network adapter HEAD suppression was not tested here. |
| Plain production HTTP Optimus image | 200, no SSL redirect/default security headers | Optimus short-circuits inner production defaults. A reverse proxy may independently supply policy; deployment was not inspected. |
| Resource selector | Leading-slash/nonpublic probe false; proper `public/...` probe true | Intended autorefresh selector is incorrect even when the file exists. |

The full machine-readable sample is
[`reports/middleware-optimus-audit.json`](reports/middleware-optimus-audit.json).
The source check and fixture measurements justify the recommendations above;
proxy caching, Safari playback, actual compiled JS savings, packaged-jar discovery
cost, and first-shell network flush timing still require their respective live or
packaged validation before implementation claims.

## Sources

Application owners: `src/backend/website/middleware.clj`, `handler.clj`,
`layout.clj`, `streaming.clj`, `concurrent.clj`, `routes/services.clj`,
`modules/main/api.clj`; `src/cljc/website/loader/style_catalog.cljc`;
`env/dev/clj/website/dev_middleware.clj`; `scripts/build/styles.mjs`;
`doc/styles.md`, `doc/ssr.md`, `doc/media-pipeline.md`.

The versioned JAR source is the evidence for implementation details, rather than
assuming current upstream branches are identical. Relevant public primary
references are [Optimus](https://github.com/magnars/optimus),
[Ring defaults](https://github.com/ring-clojure/ring-defaults),
[Ring upload handling](https://github.com/ring-clojure/ring/wiki/File-Uploads),
and [HTTP semantics, including byte ranges](https://www.rfc-editor.org/rfc/rfc9110.html).
