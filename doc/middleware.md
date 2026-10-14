# HTTP middleware ownership

`handler/app` retains the bounded virtual-thread adapter around `middleware/wrap-base`.
The production stack has one owner for each transport operation. Requests enter:

```text
proxy, HTTPS redirect and security response headers
  public error boundary
    exact Strapi webhook bearer preflight
      bounded multipart intake
        gzip representation policy
          immutable media cache policy
            known-length byte ranges and HEAD
              conditional response policy
                immutable Shadow chunk policy, length, charset and MIME
                  Optimus inventory delivery
                    ordinary public resources
                      browser cookies, form parameters, session and global CSRF
                        environment adapter and routes
```

Responses unwind in reverse. API routes retain Muuntaja content negotiation and
Malli coercion. HTML and the local-return template already supply encoded bodies;
they do not pass through a second global format encoder. The outer error boundary
returns a generic public page with the declared HTTP error status when present.
Coercion and malformed-JSON diagnostics omit raw request values and credentials.

## Browser and webhook protection

Ordinary requests retain Ring's configured cookie/session/form/CSRF behavior,
including its existing explicit API safe header. `/api/media/strapi/webhook` is
the sole external exception: only an exact POST reaches the preflight, and it
must pass `media.cms/authorize-webhook!` before an internal safe header is added.
The handler repeats that same constant-time bearer check before acquiring the
media pipeline. A path match, client safe header, missing secret, or incorrect
bearer value cannot authorize webhook work. Neighboring paths and other methods
remain subject to ordinary browser CSRF. Disabled media capabilities omit the
preflight with the existing build-time site selection.

`middleware.multipart` parses once, with a 5 MiB file limit, a total request limit
of 5 MiB plus 64 KiB, at most 16 parts including ordinary fields, and one file.
Request-owned temporary files are removed on success, handler failure, and parse
failure. Upload consumers finish reading or copying their temporary input before
returning. Reitit's multipart extension only coerces that parsed map.

## Static responses and streaming

Optimus serves GET/HEAD only. Ordinary resources have one Ring resource owner;
`/api`, `/api/*`, and `/return-worker.js` always reach their route owners. Static
responses bypass personalized sessions but retain the outer HTTPS, HSTS, frame,
and MIME protections. File and packaged-jar resources expose known lengths and
close their streams after writing or when HEAD/304/416 suppresses the body.

Single byte ranges support bounded, open-ended and suffix forms, strong ETag or
exact-date `If-Range`, and 416 with `Content-Range: bytes */length`. Unsupported
multipart ranges and malformed range syntax receive the full representation.
A zero-length representation also receives the full response. The static policy
preserves Optimus's ten-year hashed headers; successful plain `/img` and `/media`
200/206 responses use `public, max-age=31536000, immutable`. Changed media bytes
must therefore use a new filename or suffix.

Compression follows response MIME and existing encoding, not optional Fetch
Metadata request headers. Text, JSON, JavaScript, XML and SVG advertise
`Vary: Accept-Encoding`; gzip quality zero is respected even with a wildcard.
Already compressed raster images, video and fonts remain uncompressed. Conditional
responses are decided before compression, and ranges use the complete unencoded
representation. The native streaming body writes gzip directly with synchronous
flushes so the shell remains readable while deferred page data is pending.

See [the source audit](middleware-optimus-audit.md) for the recorded pre-change
behavior and [testing](testing.md) for the verification environment. Controlled
service replacements prove middleware contracts; they do not establish live
Strapi delivery, Storage publication, or deployment state.

## Prepared asset format

`website.build.assets` explicitly prepares `resources/optimus/assets.edn` from
real production Shadow outputs and built styles. Its optional `--build-styles`
argument runs the existing npm style build first. The tool keeps Optimus's current
URL inventory, independent stylesheet/document bundles, CSS reference rewriting
and legacy fallback image transformation. Shadow owns app JS URLs and minification;
the page and rendering worker share its hashed main URL without an Optimus copy.
Main's released bytes remain part of prepared-source validation. CSS imports are rejected rather
than silently incorporating another module's sheet.

The EDN artifact records the selected site, stylesheet and browser inventories,
raw source digests, original Optimus metadata and text bodies. Unchanged binary
resources remain in `public`; transformed legacy bodies use SHA256-named files in
`optimus`. The writer publishes the manifest last. The standalone importer
validates the schema, build selection, source digests and transformed bodies once,
then returns the existing Optimus asset shape. Missing, stale or corrupt artifacts
fail clearly; importing never discovers or transforms assets.

`scripts/test/prepared_assets.clj` runs inside the CPU controller's disposable snapshot.
Its `source` mode records exact bundle paths, canonical main URL/digest, metadata and body signatures; `jar`
mode compares the actual packaged resources against that record without loading
minification or image transformation namespaces. Production imports this artifact
at handler construction, once. Development loads declared CSS/JS bundles and their references through a two-second TTL; it
has no broad resource watcher. `lein with-profile uberjar,provided uberjar` and the
Docker frontend stage prepare the inventory after production Shadow/CSS outputs.
Docker copies it into the final JAR alongside public resources. These checks
establish artifact compatibility; stack/build checks verify those entry points.

Optimus bundle names remain the shared stylesheet and standalone document-script
contract. App entry/chunks retain Shadow's existing URLs and relative source-map
references; layout, preload hints and worker imports must never rename those files.
