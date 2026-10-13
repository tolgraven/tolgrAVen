# Repository tools

`bb.edn` is the local task catalog. Keep `doc/tooling.md` and caller paths current
when moving tools; tasks accept argument vectors, never interpolate a local shell.

- `build/styles.mjs` compiles shell, development inspector and module CSS entries
  with locked Sass/PostCSS, compiles the separate icon sheet and vendors
  Leaflet images. Emit UTF-8 without a byte-order marker so the same sheets can
  be inlined safely. `--if-stale` verifies source/tool/font/icon-declaration inputs
  and every raw/minified output before skipping compilation; development startup
  runs that guard before opening HTTP. Keep full builds and the watch workflow aligned.
- `test/ssr.clj` saves renderer export metadata with each HTML/state hydration
  fixture; never infer rendered exports from the browser bundle inventory.
- `bb prod:build` builds CSS and calls the existing Shadow release API sequentially
  for `:document`, `:app`, `:ssr`, and `:return-worker` with separate `prod-local-*`
  build IDs under `target/production`, then prepares the shared Optimus manifest
  into that resource root. Never copy those
  assets into watched browser/SSR outputs. See `doc/testing.md`.
- `build/`, `media/`, `dev/`, `test/`: local build, asset and verification tools.
  Prefer Babashka using its built-in libraries; Node owns SDK/zlib operations.
- `ops/`: isolated site provisioning and Coolify adapters. `ops/coolify/*.php`
  executes inside the existing Laravel container. Preserve persisted ownership
  markers even when their historical script names differ from current paths.
- `ops/host/`: installed server programs. Keep each program's declared host/service scope, recovery
  metadata, locks, graceful suspension and rollback protections intact. Relocation
  does not authorize running provisioning, seeding, deployment or installation.
  Crawl adapters own only `/robots.txt` on `bux.tolgraven.se`, `box.tolgraven.se`
  and their descendants. Default inspection must not write; explicit apply backs
  up private configuration and restores prior files on failure. See `doc/crawl-policy.md`.
- `media/images.clj` also runs in the web runtime. Keep it standalone, bounded by
  the JVM upload adapter; Docker copies and smoke-tests it with pinned Babashka.
  `--asset source-file manifest-file` emits a complete private rendition manifest;
  the durable backend owns publication only after every codec succeeds. Uploaded
  images use the same responsive cutoff as local rasters; smaller images still
  receive full-size AVIF/WebP fallbacks without sized sidecars.
- `bb images:responsive` discovers still raster originals and standalone WebP/AVIF rasters with a longest
  edge of at least 960 and source size at least 64 KiB (native width >320), and rebuilds the
  responsive catalog's sized AVIF/WebP files. Preserve manual entries, explicit
  exclusions, original/full-size fallbacks and progressive opt-outs; skip animated
  assets, uploaded avatars and generated sidecars. `images:responsive:verify`
  checks codec dimensions and byte budgets. The same generator samples oriented
  3×3 RGB pixels into three horizontal bands for initial SSR backgrounds; transparent
  pixels composite against white. `images:placeholders` refreshes only this metadata. Commit catalog and generated assets together.
- `bb videos:responsive` atomically rebuilds cataloged mobile AV1/VP9/H.264
  renditions with FFmpeg; keep the full-size video and codec fallbacks intact.
- Hook conversion reads index blobs, handles NUL-delimited paths, checks all outputs
  before staging, and preserves unstaged source and variant edits. Test with real
  codecs in disposable Git repositories.

- `bb fonts:icons` uses the optional pinned Python/fontTools authoring tool to
  export literal component/module `:icons` as SVG masks in the owning CSS and
  regenerate small icon fonts with disjoint Unicode ranges and selected selectors.
  Generate the browser name catalog from those same selected glyphs; dynamic
  unknown names acquire the retained full catalog through its module. The shared source
  reader supplies both generation and Shadow style inventories. Source literals
  and `resources/icon-fonts.json` own glyph selection; keep full-font fallbacks.
  SVG viewports must contain transformed outlines; compensate expanded mask boxes
  with margins to preserve the original advance/height/baseline.
  Commit declared SVGs, catalog, generated SCSS and fonts together; see `doc/styles.md`.

- `audit/middleware.clj` is a disposable Docker-only investigation reproducer.
  It may generate stand-in JavaScript only inside the CPU controller's snapshot;
  it must never overwrite watched application output. See `doc/middleware-optimus-audit.md`.
- `dev/cpu_controls.py` is the macOS libproc/signal and Docker lifecycle adapter.
  Keep microsecond PID identity, exact project/app ownership, pre-stopped exclusion,
  independent CONT watchdog, container labels and two shared inherited-lock build slots intact.
  Each slot caps Docker at 1.5 CPUs and 4 GiB RAM with a 2 GiB JVM heap;
  ordinary containers share only the host Maven repository directory, not credentials. Reservations sum across live workers, and
  BuildKit daemon/cache identities must differ between slots. Keep each worker
  garbage-collected toward 2 GB cache with a 256 MB reservation and 20 GB free-space
  target; preserve outputs still owned by active verification.
  Native CPU accounting uses the Mach timebase; cap tests must measure actual CPU
  seconds, not only duty settings. CPU probes touch disposable owned fixtures only.

  Ordinary compiler snapshots reuse only an independently locked, worktree/toolchain/
  profile/site-scoped `.shadow-cljs/builds` cache; transient ports and generated output
  remain fresh. The container guardian inherits its writer lock. Keep Shadow's native
  source/options validation and the unlocked 7-day/8-GiB LRU cleanup; never mount
  watched checkout caches or retain a hot JVM per worktree. See `doc/resource-controls.md`.

  Custom codec images use explicit `run --node-deps skip` only for non-Node tasks;
  retain the shared queue, snapshot and container limits. Default Node bootstrap stays enabled.

- `test/shadow_cache.py` measures the installed launcher's real cold/warm compiler
  reuse with ordinary build IDs and resource budgets. Source mutation fixtures stay
  inside isolated snapshots; keep queue/dependency/compiler time separate from wall
  time and verify that missing fresh output is written from cached analysis.

- `test/prepared_assets.clj` checks the explicit prepared inventory from source and
  then from an uberjar, preserving all bodies and independent bundle paths. Run
  it only in the CPU controller's Docker snapshot after real production outputs;
  it does not start the application or prove deployment behavior.
