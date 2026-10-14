# Optional preview dependency comparison

Measured 2026-10-14 using the normal optimized production target and its generated
source-map report. Baseline: `feddd467` (namespace integration). After: `88e6fa4a`
(module conventions and optional previews). Later image-test ordering changes do
not change the measured production sources.

Both use the same dependency prefab and lockfiles. Artifacts use gzip level 9 and
Brotli quality 11. Each compiler runs with a 1.5-CPU quota and 2 GiB heap. VM memory
and background load differed, so these results compare bytes and dependencies,
not compilation or browser timings.

## Blog prerequisites

This counts Blog itself and the transitive `:depends-on` closure from the actual
Shadow report. Each bundle is counted once.

| Metric | Before | After | Reduction |
| --- | ---: | ---: | ---: |
| Mandatory bundles | 5 | 3 | 2 |
| Raw bytes | 2,524,192 | 2,152,205 | 371,987 |
| Gzip bytes | 615,137 | 510,304 | 104,833 (17.0%) |
| Brotli bytes | 492,148 | 404,883 | 87,265 (17.7%) |

Before: `main`, `user`, `markdown`, `link-preview`, `blog`.
After: `main`, `user`, `blog`.

Blog directly depends on `main` and `user`. The former Link Preview edge also
pulled Markdown into this closure. Both now use their own managed acquisition.
The generated source inventory confirms that preview views, events, subscriptions
and the module entry remain in `link-preview`. Its small content interface,
candidate utilities and public schema are shared in `main`.

Total application gzip size barely changes: 1,827,804 to 1,827,150 bytes. The
benefit is removing prerequisites, rather than removing optional functionality.
Visible Markdown and eligible external links can still request their respective
modules. Static SSR content remains available during those acquisitions.

These are code graph sizes. They exclude CSS, fonts, data, cache effects and later
optional requests; they are not initial-route transfer or LCP measurements.

## Reproduction

In separate clean checkouts of the two revisions, build CSS and run the existing
production audit through the capped launcher:

```sh
tolgraven-cpu run -- sh -c 'npm run build && bb audit module-dependencies'
```

In each isolated output, inspect
`target/bundle-audit/module-dependencies/report.json` for `build-modules`, their
`module-id` and `depends-on`. Recursively collect Blog's unique prerequisites,
then sum the matching entries from `sizes.json`. `sources.edn` identifies the
actual resolved source owner; `report.html` provides the interactive view.
Generated reports remain build artifacts.
