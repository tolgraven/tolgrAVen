# Repository tools

`bb.edn` is the local task catalog. Keep `doc/tooling.md` and caller paths current
when moving tools; tasks accept argument vectors, never interpolate a local shell.

- `build/styles.mjs` compiles all CSS entries with locked Sass/PostCSS and vendors
  Leaflet images. Keep `npm run build` and the watch workflow aligned.
- `build/`, `media/`, `dev/`, `test/`: local build, asset and verification tools.
  Prefer Babashka using its built-in libraries; Node owns SDK/zlib operations.
- `ops/`: isolated site provisioning and Coolify adapters. `ops/coolify/*.php`
  executes inside the existing Laravel container. Preserve persisted ownership
  markers even when their historical script names differ from current paths.
- `ops/host/`: installed server programs. Keep the staging-only scope, recovery
  metadata, locks, graceful suspension and rollback protections intact. Relocation
  does not authorize running provisioning, seeding, deployment or installation.
- `media/images.clj` also runs in the web runtime. Keep it standalone, bounded by
  the JVM upload adapter; Docker copies and smoke-tests it with pinned Babashka.
- Hook conversion reads index blobs, handles NUL-delimited paths, checks all outputs
  before staging, and preserves unstaged source and variant edits. Test with real
  codecs in disposable Git repositories.
