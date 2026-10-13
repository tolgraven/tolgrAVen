# Repository tools

`bb.edn` is the local task catalog. Keep `doc/tooling.md` and caller paths current
when moving tools; tasks accept argument vectors, never interpolate a local shell.

- `build/styles.mjs` compiles shell, development inspector and module CSS entries
  with locked Sass/PostCSS, compiles the separate icon sheet and vendors
  Leaflet images. Emit UTF-8 without a byte-order marker so the same sheets can
  be inlined safely. Keep `npm run build` and the watch workflow aligned.
- `test/ssr.clj` saves renderer export metadata with each HTML/state hydration
  fixture; never infer rendered exports from the browser bundle inventory.
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
- `bb images:responsive` rebuilds the opt-in resource catalog's sized AVIF/WebP
  files. Keep original/full-size fallbacks; commit catalog and generated assets together.
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

- `dev/cpu_controls.py` is the macOS libproc/signal and Docker lifecycle adapter.
  Keep microsecond PID identity, exact project/app ownership, pre-stopped exclusion,
  independent CONT watchdog, container labels and shared build serialization intact.
  Native CPU accounting uses the Mach timebase; cap tests must measure actual CPU
  seconds, not only duty settings. CPU probes touch disposable owned fixtures only.
