# Dynamic page layout references

These reviewed references cover the four new page layout states only, using
`test/visual/page-layout-suite.json`. They do not approve unrelated suite cases.
The approval in `capture.json` records the deployed reference and review scope.
Provider widgets are checked live; these fixtures keep article/index data fixed.
Each state briefly scrolls and returns before capture to settle Chrome's initial
background raster. Without this native repaint, wide gradients differed despite
identical geometry; both scroll tiles and narrow captures were already stable.
Two independent captures match exactly after this setup; no tolerance is used.

Capture with the installed shared Chrome adapter through the normal relay:

```sh
bb test:visual --suite test/visual/page-layout-suite.json capture \
  --origin http://127.0.0.1:4112 --output /path/to/actual
bb test:visual --suite test/visual/page-layout-suite.json compare \
  --baseline test/visual/baselines/page-layout-chrome-macos \
  --actual /path/to/actual --output /path/to/report
```

Use matching Chrome/macOS/font conditions. Review intentional UI changes before
replacing any reference; capture and compare never grant approval automatically.
