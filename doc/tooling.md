# Tool ownership and task entry points

Install Babashka 1.13.225 or newer (`brew install babashka` on macOS). `bb tasks`
shows the local command catalog. Docker builders and the upload runtime pin
1.13.225 by the official multi-architecture image digest. No Maven download is
needed to run these tasks; they use Babashka's built-in libraries.

| Owner | Commands / programs | Callers and runtime |
| --- | --- | --- |
| Build | `bb docker <action>`, `bb audit <label>` | Make targets and developers; Docker/Lein/Node subprocesses |
| Build | `scripts/build/sync-vendor.mjs`, `bundle-sizes.mjs` | npm vendor task and bundle audit; Node provides SDK resolution and Brotli |
| Images | `bb images`, `bb images:verify`, `bb images:staged`, `bb images:responsive` | Manual assets, tracked pre-commit hook; cwebp/ImageMagick |
| Uploads | `scripts/media/images.clj` | JVM upload adapter and Docker codec smoke test; standalone `bb` script |
| Videos | `bb videos [--force] [files...]`, `bb videos:responsive` | Manual assets; FFmpeg VP9/AV1, muted output, original MP4 fallback |
| Development | `bb hooks`, `bb pair <operation>` | Per-checkout setup and installed re-frame-pair skill |
| Renderer tests | `bb ssr:fixtures [--worker path]` | Actual compiled generic Node renderer; generates browser hydration fixtures |
| Browser tests | `bb test:browser [--port 4002]` | Static component suite and fixtures; loopback HTTP-kit server |
| Live tests | `bb test:integration [options...]` | Python streaming HTTP proxy; preserves early SSR flushes and upstream responses |
| Tool tests | `bb test:scripts` | Babashka contracts and existing Python operations/media harness; disposable Git repos, no deployments |
| Supabase | `bb supabase <schema\|import\|all> [args...]` | Calls the native JVM CLI; container has its small `ops/entrypoint-supabase-provision.sh` launcher |
| Site / CMS | `scripts/ops/provision-site.py`, `provision-strapi.py` | Explicit operator commands; validated manifests, SSH and readiness checks |
| Coolify | `scripts/ops/coolify/*.php` | Sent over SSH into Coolify's Laravel container; uses its models, queue and transactions |
| Host services | `scripts/ops/host/` | Explicitly installed on bux; deployment recovery, staging Supabase policy, runtime reconciliation, controlled seeding and registry bootstrap |

PHP is not part of the site's runtime or builder. These adapters need Coolify's
PHP application context; replacing them with local Clojure would require another
remote API or bypass its models. The existing host Python programs keep their
locking/recovery contracts and require no new runtime installation on bux.
The live test proxy stays Python because it streams each upstream chunk and
flushes it immediately; the static suite server uses Babashka.

The flat folder's small shell wrappers and local Python Docker/media/renderer
orchestrators are replaced, not retained as compatibility copies. Make remains a
thin entry point for existing Docker workflows. Docker copies only the vendored
SDK tool into its builder and the standalone image converter into its runtime;
it does not ship provisioning or test tools.

Image and video conversion skips current variants, supports explicit paths and
spaces/newlines, reports failures, and atomically replaces each successful encode.
The Git hook validates all variants before staging any, so one failed encode does
not partially stage its batch. `images:verify` exits unsuccessfully for missing
variants; it never claims codec or live-storage validation.

`resources/responsive-images.edn` opts local originals into sized AVIF/WebP
sources. `bb images:responsive` rebuilds these with ImageMagick; commit all outputs
and preserve original/full-size fallbacks. The validated catalog is baked into
CLJS and read by server preloads, so `imagesrcset` and `<picture>` select the same
resource. Pictures default to `sizes=100vw`; callers may supply a narrower slot.

`resources/responsive-videos.edn` opts local videos into smaller native media-query
sources. `bb videos:responsive` generates AV1, VP9 and H.264 renditions atomically;
commit them with the catalog. Full-size codec sources remain available for desktop.

Runtime configuration is described in [config/README.md](../config/README.md).
Unfinished project notes remain under `experiments/notes/`; prototype source remains
under `experiments/clj/`. Tool-mandated root configuration stays at its native path.

CSS build and watch entry points compile shared and feature sheets independently;
see [styles.md](styles.md) for declarations, source ownership and verification.
