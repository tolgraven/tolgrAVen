# Managed media

CMS media and authorized Markdown uploads use one durable rendition pipeline.
The main module owns `site_media`; `tolgraven.media.schema` owns public asset,
upload/status, library and webhook contracts. `tolgraven.media.query` creates the
same scoped queries used by JVM page plans and managed browser subscriptions.

A request verifies its author or CMS webhook token, bounds and identifies its
source bytes, and uploads the original to the private `media-originals` bucket
before accepting the queued job. Decoding belongs to the worker. Supported inputs
are still PNG, JPEG, WebP and AVIF, up to 5 MiB and 4096 in each dimension;
animations and corrupt inputs never produce a ready manifest. Supplied filenames
are never passed to a codec.

One mounted worker per server claims a PostgreSQL lease, normalizes orientation
and metadata, then uses the existing standalone `media.images/asset!` generator.
Candidates use `[80 160 320 640 960 1280 1920 2560]` below the actual normalized
width, plus full-size WebP/AVIF and a normalized PNG/JPEG fallback. Each public
object path includes its actual content digest under the source hash and
`policy-version`. A manifest becomes `ready` only after every object upload
succeeds. Interrupted processing is recovered from private Storage and the durable
lease; partial objects cannot publish a partial-ready manifest.

PostgreSQL bounds pending work to 64 jobs. Claims prioritize expired leases,
serialize each claim with row locking, and carry a fresh worker token. Completion
checks that token, processing state and active source. Leases expire after fifteen
minutes; processing has three attempts with durable 15/30-second retry backoff.
The codec process has a three-minute deadline, one ImageMagick worker, bounded
pixel-cache resources, lowered priority and owned process/file cleanup. The
existing avatar converter keeps its smaller deadline. A repeated explicit upload
can requeue a failed matching job; successful matching uploads reuse their asset.
Increment `policy-version` when changing the rendition policy or codec behavior.

`site_media` has RLS and explicit column grants: public readers see only ready
IDs, source/canonical URLs, assets, status and update time. Ownership, source
objects, metadata and leases are server-only. Writes and job RPCs require
`service_role`; ordinary client tokens cannot enqueue or mutate the table or
write Storage objects. The browser never receives service or CMS credentials.

## Rendering and editor contracts

An asset contains `:id`, canonical `:src`, intrinsic `:width`/`:height`, and
`:renditions` with one `:original` and ascending vectors of `:webp`/`:avif`
candidates. Each rendition supplies its explicit URL, dimensions, byte count and
MIME type. Optional `:placeholder {:colors ["#rrggbb" "#rrggbb" "#rrggbb"]}`
contains exactly three sampled bands. The existing codec helper samples an oriented
3×3 RGB grid, composites transparency against white and averages each horizontal
row. Catalog images and managed uploads share this schema; the rendered image
owns the fixed vertical gradient and geometry. No CSS or URLs arrive in this field.
Consumers use these manifests rather than inventing URL extensions.

| API | Authorization | Result |
| --- | --- | --- |
| `POST /api/supabase/media` (multipart `file`) | Verified Blogger/Admin | `{id,status,asset when ready,error when failed}` |
| `GET /api/supabase/media/:id` | Owner or Admin | Same upload/status contract; other owners get 404 |
| `GET /api/supabase/media?offset=0&limit=24` | Verified Blogger/Admin | `{assets,next-offset}`; maximum 50 |
| `GET /api/media/resolve?src=...` | Public | `{asset}` when a ready manifest exists, otherwise `{}` |
| `POST /api/media/strapi/webhook` | Dedicated webhook bearer secret | Accept, reuse metadata, or detach the CMS association |
| `POST /api/media/strapi/reconcile?offset=0` | Verified Admin | One page of at most 20 CMS files and a continuation offset |

Bloggers' picker lists their ready uploads and active CMS assets; administrators
can list all active ready assets. Library, upload and status use the same asset
owner. Pagination and authentication belong to the existing request effects and
managed bindings, not component renders.

Post saving derives ready manifests from Markdown image URLs on the server and
persists them as public `image_assets` JSONB in the same post transaction. Client
asset metadata is rejected. The post RPC's optional sixth parameter defaults to
`{}`, preserving existing five-argument callers. Normalized posts expose
`:image-assets`, so SSR, hydration, preview and ordinary Markdown use one paired
metadata snapshot. The save boundary permits at most 64 inline image references.

CMS snapshot acquisition batches the same public manifest queries. Image prop
maps gain explicit dimensions/renditions and `:asset`; existing string logos keep
their source string and use the owning section's `:image-assets` sidecar. Missing
manifests or an unavailable media service retain original images. The image
component's existing runtime owns progressive display and cached/final readiness.

## Configuration and operation

Media processing defaults to `{:media {:enabled false}}` in the tracked
configuration examples and production defaults. Before enabling it, apply the
module-derived Supabase schema with the existing provisioning workflow described
in [Supabase tables](supabase-tables.md), including the `site_media` and updated
`blog_posts` resources. This PR does not apply schema or deploy remote services.
Keep the main module's `:media` storage feature enabled for this pipeline.

Use private environment configuration or an injected `-Dconf` file, for example:

```clojure
{:media {:enabled true
         :strapi-media-origins ["https://cms.example.com"]
         :allow-private-cms? false}}
```

Supply the existing server-only Supabase URL/service key and `STRAPI_URL` /
`STRAPI_READ_TOKEN` settings. Set `STRAPI_MEDIA_WEBHOOK_TOKEN` to a dedicated
random secret of at least 16 characters (32 random bytes is appropriate); the
private `:media :strapi-webhook-token` setting is also supported. Configure a
Strapi webhook for `media.create`, `media.update`, `media.delete` with
`Authorization: Bearer <secret>` and the site webhook URL. The read token must
allow the Upload plugin's file inventory as well as the existing content export.

Relative CMS `/uploads/<file>` URLs resolve against the configured CMS. Additional
exact origins are opt-in; redirects, credentials in URLs, arbitrary paths and
queries are refused. DNS is checked at connection time. Private CMS networking
requires explicit `:allow-private-cms? true` and applies only to the CMS's own
origin; read credentials never go to another configured media origin. Downloads
have size, connection, socket and overall read bounds. Existing managed
`media-renditions` URLs are ignored to avoid webhook processing loops.

Metadata-only updates reuse pixels when the Strapi file hash and URL match.
Changed content hashes create a new immutable version. Delete events cancel
uncompleted work and detach source mappings, while retaining immutable pixels
that published posts or cached documents may still reference. Reconciliation
uses the installed Strapi Upload API's bounded pagination and returns a
continuation offset; repeat it to cover an existing library. Storage retention
or removal is an explicit operator action, not an automatic delete event.

## Verification

Run backend contracts and real codec fixtures through the installed CPU launcher.
The ordinary dependency prefab includes ImageMagick and WebP tools and verifies
WebP/AVIF encoding and decoding during its build. Refresh it with the bounded
BuildKit workflow in [Docker builds](docker-builds.md) when changing its toolchain.

```sh
tolgraven-cpu run -- lein with-profile +project/test test tolgraven.media-pipeline-test tolgraven.image-conversion-test tolgraven.supabase-catalog-test
tolgraven-cpu run -- bb --classpath scripts:test/scripts -m media-asset-test
```

`test/scripts/media_sql_test.sql` runs in a disposable PostgreSQL fixture and
verifies fresh DDL, queue capacity/deduplication, public grants/RLS, expired leases,
stale completion and backward-compatible post persistence. It must never run on a
live project database. Codec fixtures decode pixels and verify orientation and
candidate dimensions; HTTP fixtures exercise actual Storage/CMS adapters. These
checks do not establish live Strapi webhook configuration or remote deployment.

The storage upload/immutable URL rules follow the [Supabase upload API](https://supabase.com/docs/guides/storage/uploads/standard-uploads)
and [Storage access control](https://supabase.com/docs/guides/storage/security/access-control).
Strapi media envelopes and bearer headers follow its [webhook contract](https://docs.strapi.io/cms/backend-customization/webhooks).
