# Pixel and layout regression gate

`bb test:visual` compares native component rendering. It fails on **one changed
RGBA pixel**, element box, relative position, padding, margin, border, font, gap,
overflow, missing element/icon, or browser/font/viewport environment difference.
Native horizontal and vertical scroll offsets are recorded alongside scroll dimensions.
Both `::before` and `::after` generated content record computed dimensions,
offsets, spacing, borders, backgrounds, masks and typography for every element.
These computed pseudo-element metrics supplement the native parent boxes;
raster comparison verifies their actual painted pixels.
There is no blanket tolerance, ignored region, automatic baseline update or
animation-disabling stylesheet.

This supplements interaction, cold-load, hydration and motion checks. Settled
images cannot prove an expansion animates. Fixed fixtures do not prove live
provider behavior or protect module states absent from the suite.

## Build and capture

Compile through the existing bounded worker, preserving watched output:

```sh
tolgraven-cpu run --memory 6g --java-heap 3g -- sh -c 'npm run build && lein with-profile +project/test run -m shadow.cljs.devtools.cli --config-merge '\''{:ns-regexp "^website\\.visual-fixture$"}'\'' compile app-test'
```

The explicit `--config-merge` namespace expression above is required: the default
`app-test` expression discovers test namespaces and excludes `website.visual-fixture`.
The full module graph needs the demonstrated 3 GiB heap. From the printed
**isolated output directory**, run `bb test:browser --port 4098`. The fixture at
`/fixtures/visual.html` reuses the CMS seed and production components, fixes only
provider data, and retains native handlers, Markdown, measurement and
module loading. `test/visual/suite.json` declares exact states and viewports.
The fixture anchors its calendar to 2026-10-15 so ongoing CV entries keep stable
endpoints across calendar years. Wall time still advances with `performance.now`;
native timers, deadlines and finite motion retain their normal lifecycle.
Settled layout cases warm the declared native Fira Code face before mounting and
wait for committed font phases. This prevents cache timing from choosing between
equivalent native/ready font aliases. The separate font suite exercises cold
fallback and crossfade behavior.
Shell fixtures keep the header and full footer outside `main`, as in the native
page. The combined shell includes the sticky footer; the full footer owns its
post-footer content exactly once. The post-footer case exercises that owning
footer context rather than rendering its flex content directly in the main grid.
Services and CV fixtures use a native `defpage` container so the main grid cannot
stretch individual sections. Isolated CV boxes retain the production timeline
column width/height; absolute-positioned entries cannot establish those dimensions.
Their dates determine position/size within the seed's shared timeline range;
the case scrolls the native timeline to that historical entry before capture.
The original editor comparison fixture remains unchanged.
Home cases retain native intro/story/media owners and the public seed. Story
image expansion clicks the actual inset image. Gallery start/end cases set their
explicit horizontal position after image decoding, because native mandatory
scroll snapping can select a different slide while image widths are settling.
Post/comment cases render the native blog container and post, including the
post-owned comment section. Standalone comment cases use the native standalone
view separately. Thread cases supply two replies and a grandchild through the
managed comment subscription boundary; the production reply components own
their nesting and layout. Search cases render the native expandable Search UI
outside main, with fixed results/suggestions instead of a synthetic padded section.
The fixture head uses Highlight's declared monospace/Markdown sheets and the
styled input's declared sheet, matching the native owners rather than a nonexistent
`highlight.css` file.

Keep the installed Chrome running with its configured DevTools connection:

```sh
bb test:visual capture --origin http://127.0.0.1:4098 \
  --geometry-only --output .local-wip/visual/candidate
```

The runner defaults to a JSON-lines relay through the **existing configured MCP
session**, creates one owned tab, reuses it and closes it in cleanup. It never
launches a separate browser, quits Chrome, clears storage or modifies another tab.
An MCP-capable driver forwards each `{"visual_tool":"tool_name","arguments":{...}}`
line to its configured Chrome tools and writes the unmodified MCP result back
as one JSON line. Image blocks stay in the protocol, never the human transcript.
For large results, write the complete JSON result to a task-owned local file and
return `{"response_file":"/absolute/task/path/result.json"}` instead; the runner
reads the identical result without terminal payload limits.
The capture process owns all ordering, readiness, state changes and saving; the
relay supplies only transport. With an agent exec tool, allocate a task-owned PTY
so stdin remains available between calls. The runner disables echo/canonical mode
on that PTY to safely accept full screenshot payloads.

For standalone attachment use
`--mcp-command /Users/tol/.local/bin/codex-chrome-devtools`. The runner accepts only
that configured launcher, which reuses port 9223 and the persistent Development
profile and opens that same installed Chrome profile if needed. Never substitute
the daily profile, `--autoConnect` or a throwaway browser after a crash.

Capture waits for native mounting, loaded consumer fonts, decoded required images and
finite motion, then pauses infinite animation clocks at zero before checking
stable geometry/computed appearance. Eager, completed and visible lazy
images must decode; offscreen pending lazy images retain native loading policy.
A selected `currentSrc` or cached dimensions alone do not release Chrome’s lazy gate. Each
readiness call, including every scroll tile, reevaluates their visibility and
acquisition. This prevents an offscreen footer from blocking a scroll that would
otherwise acquire its lazy images. A fading caret otherwise never has stable
opacity. Native error fallbacks, failed images, unsettled layout and an
unexpected viewport/DPR fail capture. Set only this fixture origin's Chrome zoom
to 100%; do not hide environment changes behind a tolerance.

`--case cv-regular-open-narrow` limits diagnosis to one case. The full comparison
still requires every declared case; missing captures cannot silently pass.
`--geometry-only` explicitly captures element geometry/computed appearance without
PNGs for a faster layout-only gate. It must match the approved baseline's mode;
it cannot silently downgrade a pixel baseline. Use raster captures for states
whose painted appearance is under review. Its driver mounts one same-origin
native fixture frame at a time with exact viewport dimensions, collects its
actual DOM and removes it before the next state. Raster captures use the actual
top-level viewport; both modes retain normal component lifecycles.
Raster mode scrolls the document from its top through overlapping viewports to
its bottom, saving each viewport's PNG and geometry separately. This covers
expanded code and trailing text below the fold without a full-page screenshot
that can change native painting. Reports compare every tile and its scroll
coverage; a missing tile fails. Nested independently scrolling regions still
need explicit suite states for their additional positions. The bounded runner
rejects documents requiring more than 128 tiles.

## Native CSS motion contracts

New captures include `native-css-motion-v1` alongside geometry in both modes.
An observer is installed before native fixture initialization and marks initial,
action and raster-scroll phases before those actions run. It records every
activation of native `CSSAnimation` and `CSSTransition` objects: target/pseudo,
name or property, full `KeyframeEffect.getTiming()` and `getKeyframes()`, composition
and playback rate. Paused/idle objects do not count until they run; native pointer
and focus/keyboard events wake observation so resuming the same object is captured.
Delayed running objects are recorded while pending; finite and zero-duration
objects remain in the record after finishing. Start/current times, frame clocks
and elapsed progress are deliberately excluded. Infinite clocks are measured
before the existing raster alignment pauses them.

Snapshots also record the computed animation/transition declaration list for
every light-DOM element and standard pseudo, plus stylesheet-declared and
observed runtime pseudos. A shared style table keeps repeated contracts compact.
This includes inactive hover/state declarations, while actual triggers prove
only the checked-in interactions. Zero-duration changes, easing/delay/keyframe
changes, missing triggers and pseudo changes fail exact comparison even when the
settled geometry and pixels match. Unobservable native start events, inaccessible
stylesheets and embedded/shadow roots needing separate observation are explicit
capture gaps; they cannot be approved as complete observations. Add native
interaction cases for states not exercised by the suite.

Motion coverage requires a separately reviewed candidate and a repeat capture:

```sh
bb test:visual compare --require-motion --baseline <reviewed-motion-baseline> \
  --actual .local-wip/visual/candidate --output .local-wip/visual/motion-report
```

Old layout-only baselines stay layout-only. Mixing them with a motion capture
fails with a coverage message; `--require-motion` also rejects two old captures.
No command populates approval or refreshes the existing approved layout files.
Reports include exact motion paths independently of geometry/pixel changes.

The live `/__tests/motion.html` driver, served through the unchanged-HTTP proxy
in `scripts/test/integration_server.py`, also leaves the application for the
other loopback hostname (`localhost` versus `127.0.0.1`), then traverses genuine
joint session history Back. It observes the retained document across BFCache
and new documents from their first accessible parent frame, before any return
interaction. It requires native retained-document or `back_forward` evidence,
no newly triggered or running CSS animations/transitions (including pseudos),
and observes for 3.5 seconds, covering the native two-second title delay. It
stores bounded observation evidence in `#results.dataset.externalBack`. It does
not rewrite application HTML, seed return state or forge navigation timing.
The existing ordinary Search and navigation interactions follow restoration;
return suppression must not disable subsequent user-triggered motion.

This parent-frame check cannot claim observation before a new document is
accessible, nor prove indefinitely delayed/provider-triggered behavior after its
bounded window. Keep cold/paired/external Back live evidence separate from fixed
fixture parity. Browser execution and a repeat remain required; Node adapter
checks alone establish neither actual CSS timing nor BFCache behavior.

## Approve and compare

Captured candidates are **not approved design baselines**. Compare against
`tolgraven.se`, using earlier Git history for already deployed regressions. Do
not approve current broken UI to turn the gate green. Inspect both widths and
every edge of controls/text before accepting images and geometry.

Store reviewed PNG/JSON files and `capture.json` in a versioned directory such as
`test/visual/baselines/chrome-macos-geometry/`. Record explicit review evidence in its
`capture.json` `approval` field:

```json
{"reviewer":"reviewer name","reference":"tolgraven.se and reviewed repair PR",
 "reason":"Original design verified in every declared state"}
```

Approval is an explicit review step; capture and compare never populate it.
Review baseline changes as UI changes in the PR. Do not refresh goldens during
a technical refactor. Suite/content changes need reviewed baseline changes too.
Chrome, OS/font and rasterization changes require reviewed captures in a separate
environment. The versioned `chrome-macos-geometry` baseline covers the reviewed
suite in explicit geometry mode. Its approval records the review scope; it does not claim pixel or
motion parity. Capture it with `--geometry-only` at exactly
`http://127.0.0.1:4098`: computed asset URLs retain their real origin, so a changed
origin correctly fails the exact comparison. Keep raster approvals separate.

```sh
bb test:visual compare --baseline test/visual/baselines/chrome-macos-geometry \
  --actual .local-wip/visual/candidate --output .local-wip/visual/report
```

Exit 0 means all declared cases match exactly. Exit 1 means visual/layout
failure; exit 2 means setup/capture/approval failure. The HTML report shows
expected, actual and highlighted changed pixels, with geometry/property paths.
`report.json` gives changed-pixel counts/bounds and exact element differences.

Run this gate before merging technical UI changes. CI can run the same
dependency-free comparison on captures from the baseline's approved Chrome/OS
runner. An unprovisioned Linux runner cannot verify macOS Chrome goldens; do not
present the comparator's unit tests as a passing visual gate. Retain baseline,
actual and report directories as review artifacts on failure.

The comparator's own test deliberately changes a pixel, subpixel position,
padding and icon presence:

```sh
python3 -m unittest discover -s test/scripts -p visual_test.py
```

The motion adapter checks native-object retention, phase attribution, pseudos,
delays and cleanup; comparator checks deliberately mutate duration, easing,
keyframes, transition properties, pseudo declarations and missing triggers,
then restore an exact pass:

```sh
node --test test/scripts/visual_clock_test.cjs test/scripts/visual_motion_test.cjs
python3 -m unittest discover -s test/scripts -p visual_test.py
```
