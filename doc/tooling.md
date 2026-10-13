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

`bb images:responsive [--force]` discovers JPEG/PNG originals and standalone WebP/AVIF rasters under `resources/public/img` with a longest edge of at least 960 **and**
at least 64 KiB of source data. Both thresholds must pass; native width must also
exceed 320 so horizontal downscaling is useful. Portraits qualify by height. It
skips vectors, animations, uploaded avatars, app icons and generated `-<width>w`
sidecars. The defaults come from the current asset inventory: large CV logos,
showcase screenshots and blog/header photos qualify while small compressed icons
do not. Images retain their original and existing full-size codec fallbacks.

The existing `resources/responsive-images.edn` remains the single catalog baked
into CLJS and server preloads. New entries have `:generated? true`, refreshed
intrinsic dimensions and widths from `[80 160 320 640 960 1280 1920 2560]` below the
original width. Existing manually curated sizes are preserved. Remove
`:generated?` to take manual ownership of an entry; `:progressive? false` disables
its preview while retaining responsive sizing. An explicit `{:exclude? true}`
entry prevents discovery and is omitted from the runtime catalog. Exclusion never
deletes image files. Animated manual entries fail rather than silently flattening.

`bb images:responsive:verify` reads every sized codec, rejects upscales or incorrect
widths and reports the total byte budget. This verifies file metadata, not visual
quality or browser decoding.

Sized encodes use one ImageMagick worker, strip metadata, replace outputs
atomically and skip current variants unless `--force` is supplied. The catalog is
published after all encodes succeed. Commit it together with all new variants.
On this Mac, use the installed shared-slot CPU launcher with the ordinary codec-capable dependency
prefab for generation (`tolgraven-cpu run -- bb images:responsive`); its isolated snapshot retains outputs for deliberate copy
back to this worktree (never overwrite a watched output directory). Pictures
and preloads share the same candidates; callers may supply a narrower `sizes`
slot than the default `100vw`.

Representative committed AVIF sizes illustrate the per-request savings; browsers
choose one final candidate for the declared slot/DPR, rather than downloading the
whole catalog. Values below are file bytes, not timing measurements.

| Source | Full AVIF | 80-wide preview | 320-wide final |
| --- | ---: | ---: | ---: |
| CV `tolgraven-logo.png` | 20,798 | 805 | 2,019 |
| Showcase `wordgame.png` | 25,755 | 2,015 | 10,074 |
| Showcase `joen-data-viz-better.png` | 53,654 | 641 | 3,404 |
| Photo `skuggan-dansbana-small.jpg` | 240,103 | 1,150 | 9,391 |

`resources/responsive-videos.edn` opts local videos into smaller native media-query
sources. `bb videos:responsive` generates AV1, VP9 and H.264 renditions atomically;
commit them with the catalog. Full-size codec sources remain available for desktop.

Runtime configuration is described in [config/README.md](../config/README.md).
Unfinished project notes remain under `experiments/notes/`; prototype source remains
under `experiments/clj/`. Tool-mandated root configuration stays at its native path.

CSS build and watch entry points compile shared, icon and feature sheets independently;
see [styles.md](styles.md) for declarations, source ownership and verification.

`bb audit <label>` writes isolated optimized chunks, compression sizes, the
namespace/module inventory and `report.html`/`report.json` under
`target/bundle-audit/<label>`. The standalone Shadow report attributes optimized
bytes through source maps; inspect those contributions before choosing a new
module boundary. Source maps/report generation are confined to this audit build.
The audit rejects known optional implementations entering `:main`, including
landing views, the custom input, pprint/data inspection, Markdown-clj, maps and
highlighting. The rendered Markdown module uses Marked; importing an unused second
parser into the shell would retain it in the initial bundle.

All release browser, Node renderer and return-worker builds reject 10x, re-frisk
and the legacy inspector entry point before compilation, including isolated audits.
This check reads the resolved graph. Shadow retains analysis for removed imports,
so the presence of a namespace under `release/ana` does not establish bundle ownership.

Audit labels create separate compiler caches under `.shadow-cljs/builds/audit-*`.
Periodically remove completed labels after inspecting active workers. Keep the
current audit's cache for incremental iterations, watched `app-dev`/`ssr`/
`return-worker` caches, and useful reports/chunks in `target/bundle-audit`.
Do not clear the entire Shadow directory while watches are running.
