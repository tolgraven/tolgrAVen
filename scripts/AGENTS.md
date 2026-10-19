# Repository tools

`bb.edn` is the local task catalog. Keep `doc/tooling.md` and caller paths current
when moving tools; tasks accept argument vectors, never interpolate a local shell.

- `test/visual.py` attaches through the configured shared Development-profile
  launcher (`codex-chrome-devtools`) and closes only its own reused task tab.
  Identify that new tab by its unique task URL and fresh observed page ID, never selection.
  Raster capture awaits startup readiness before positioning tile zero, then
  checks readiness and exact bounds after each scroll.
  Capture creates candidates; strict pixel/geometry comparison never updates
  approved baselines. Per-case/tile records may use lossless `.json.gz`; reject
  ambiguous encodings and retain every field and plain approval metadata. Preserve
  environment checks and missing-case failures. Default motion/ID comparison stays exact.
  Explicit definitions and reserved React client-ID policies retain raw differences
  and label captured native-instance status in JSON/HTML. They never refresh goldens
  or establish identical runtime execution/accessibility relationships. Decode fenced evaluation results
  with the JSON parser before checking their closing fence: rendered Markdown can
  contain literal fences inside the payload. See `doc/visual-regressions.md`.

- `build/styles.mjs` compiles shell, development inspector and module CSS entries
  with locked Sass Embedded/PostCSS, reuses one asynchronous compiler across each
  build's sequential entries and disposes it on success/failure, compiles the separate icon sheet and vendors
  Leaflet images. Emit UTF-8 without a byte-order marker so the same sheets can
  be inlined safely. `--if-stale` verifies source/tool/font/icon-declaration inputs
  and every raw/minified output before skipping compilation; development startup
  runs that guard before opening HTTP. Keep full builds and the watch workflow aligned.
- Production preparation invokes `build/prune-styles.mjs` after release, using
  shipped chunks and selected sheets. Keep variable/animation liveness shared
  across lazy sheets and preserve runtime selector families and attribute states.
  Development CSS/watch output and bundled editor SCSS remain complete;
  generated-output exclusions must not filter source files. See `doc/styles.md`.
- `test/server.clj` decodes only the two pinned Docs HTML fixtures from
  `test/fixtures/docs`; keep their reviewed decoded bytes stable across source
  changes. Current Codox generation is a separate integration check.
- `test/server.clj` accepts `--glass-output` for isolated glass fixture output;
  it serves current shared CSS and leaves ordinary browser-suite routing intact.
- `test/ssr.clj` saves renderer export metadata with each HTML/state hydration
  fixture; never infer rendered exports from the browser bundle inventory.
- `bb prod:build` builds CSS and calls the existing Shadow release API sequentially
  for `:document`, `:app`, `:ssr`, and `:return-worker` with separate `prod-local-*`
  build IDs under `target/production`, then prepares the shared Optimus manifest
  into that resource root. Never copy those
  assets into watched browser/SSR outputs. See `doc/testing.md`.
- `build/`, `media/`, `dev/`, `test/`: local build, asset and verification tools.
  Prefer Babashka using its built-in libraries; Node owns SDK/zlib operations.
- `build/docker.clj` hashes the dependency recipe/manifests and bundled runtime
  converter and publishes the native compiler/runtime prefab. Coolify advances aliases from master. The staged
  commit pin must use index bytes and preserve unstaged edits. PR readiness is
  checked by the Coolify gate against that exact hash on ARM64/AMD64 runtimes; missing/auth
  failures must not be treated as a successful cache hit or bypass the merge gate.
  Keep the fallback recipe identical after normalizing stage names/platform flags.
  `provision-site.php` reconciles Docker build settings on existing applications:
  normal build caching, explicit CMS secrets, no automatic ARG injection, and
  runtime-only unrelated keys. Same-source CMS feature/CSS changes need a forced rebuild.
  Domain changes prepend the canonical origin and retain existing proxy aliases;
  CMS rewiring must preserve ordinary compiler caching and existing credentials.
  Dockerfile owns the prefab default and persistent Maven cache on every host.
- `ops/entrypoint-supabase-provision.sh` runs the native CLI with the isolated
  `:provision` profile; its image supplies production environment sources/defaults.
- `ops/`: isolated site provisioning and Coolify adapters. `ops/coolify/*.php`
  executes inside the existing Laravel container. Preserve persisted ownership
  markers even when their historical script names differ from current paths.
  CMS upgrades reconcile the write-token Compose reference on existing services;
  storing a credential alone does not install it into a running container.
- `ops/host/prefab_gate.py` is the trusted Coolify event gate. Preserve signed
  delivery validation, same-repository trust checks, durable deduplication, stale
  head checks and independent close-event handling. Only BuildKit may execute PR
  recipes. The App identity stays in the Laravel adapter; no Actions deployment
  relay or registry credential copy belongs in GitHub. See `doc/docker-builds.md`.
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
  export literal component/module `:icons` as native glyph rules in owning CSS and
  retain SVG assets, selected selectors and the backend codepoint selection manifest.
  Generate the browser name catalog from those same selected glyphs; dynamic
  unknown names acquire the retained full catalog through its module. The shared source
  reader supplies both generation and Shadow style inventories. Source literals
  and `resources/icon-fonts.json` own declared glyph selection. The backend CMS sync
  runs the packaged `resources/fonts/subset.py` codec; active CSS has no full-font fallback.
  SVG exports must retain complete outlines. Native font glyphs own original
  rasterization and fractional line metrics; do not substitute SVG masks in controls.
  Commit declared SVGs, catalogs, generated SCSS, codepoint selection and fixture
  font/CSS baselines together; see `doc/styles.md`.

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
  source/options validation and the unlocked 7-day/2-GiB repository LRU cleanup; never mount
  watched checkout caches or retain a hot JVM per worktree. Cache storage belongs
  to the primary checkout's ignored `.local-wip/shadow-caches`; metadata identifies
  the originating worktree and disappeared owners are pruned immediately. See `doc/resource-controls.md`.

  Custom codec images use explicit `run --node-deps skip` only for non-Node tasks;
  retain the shared queue, snapshot and container limits. Default Node bootstrap stays enabled.

- `test/shadow_cache.py` measures the installed launcher's real cold/warm compiler
  reuse with ordinary build IDs and resource budgets. Source mutation fixtures stay
  inside isolated snapshots; keep queue/dependency/compiler time separate from wall
  time and verify that missing fresh output is written from cached analysis.

- `test/prepared_assets.clj` checks the explicit prepared inventory from source and
  then from an uberjar, preserving all bodies, independent bundle paths and the
  canonical unbundled main URL/digest. The real middleware check includes main. Run
  it only in the CPU controller's Docker snapshot after real production outputs;
  it does not start the application or prove deployment behavior.

- The browser fixture server's optional `--editor-cms-origin` accepts only a
  disposable loopback CMS. Its private fixture tokens proxy field/CV writes and
  optional live fixture reads. Upload status is simulated there; it does not
  establish Storage/codec publication or production session authorization.

- `dev/cleanup.py` defaults to a report. Applied worktree retirement requires an
  exact merged PR head or recorded merge commit in `origin/master`, no open
  follow-up on its branch, clean source and no process using the target. Preserve the main checkout and detached/ambiguous work; archive ignored
  private files and review records before ordinary, non-forced worktree removal.
  Never infer browser ownership or close browsers from a Git hook. Agents close
  their own verification tabs and servers when finished; the script preserves
  existing compiler writer locks and cannot replace session ownership checks.

- `dev/pr.clj` owns `bb pr` description attribution and the tracked
  `prepare-commit-msg` session trailer hook. Preserve the original owner and
  recorded contributors on every body rewrite; attribution comes only from
  explicit IDs, the current session, and commit trailers. `session_metadata.py`
  reads only their recorded names and working directories; never publish prompts,
  previews or transcript text. Preserve former names/paths in the managed footer
  when local metadata changes or disappears. See `doc/tooling.md`.
- Static Neovim fixtures reuse the module's portable isolation policy for their
  document/worker paths; the explicit unsupported alias has no document isolation.
  Keep the direct `:neovim-check` bundle route separate from `:app-test` resources.
  Standalone visual cases await an optional owned disposal hook on success/failure
  before the next navigation; active WASM cases must never use the iframe batch.

- Host SSD migration is explicit through `ops/host/coolify-storage.py`: verify the
  provisioned filesystem, drain deployments, preserve originals until live
  verification, and require mounts before Docker starts. Never format implicitly.
  Scheduled `coolify-storage-cleanup.py` owns only aged managed deployment images
  and unused Docker cache; preserve rollback/container references and all volumes.
  Its timer must skip inactive Docker and missing mounts without activating either.
  See `doc/coolify-storage.md`.

- `build/compiler-cache.mjs` runs only within a locked deployment BuildKit mount.
  Preserve unchanged macro/resource timestamps by content; changed bytes must get
  a different timestamp. AOT reuse requires the complete input/toolchain/options
  identity and publishes only successful output. Never share writable compiler
  output with live watches or let failed/removed sources leave reusable classes.

  Warm deployment compiler measurements must not use Docker `--no-cache`: it
  clears matching cache mounts. Rerun normal build instructions and verify actual
  compiler counts/restored AOT classes separately from whole-layer hits.
