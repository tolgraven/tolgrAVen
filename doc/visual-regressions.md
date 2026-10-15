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

Docs cases render the native Docs page directly under main and read the actual
Codox index/topic generated in the served build. Run `lein docs` through the bounded
worker when preparing that snapshot; `/fixtures/docs/index.html` and
`/fixtures/docs/docs.html` expose only those generated files. The fixture applies
the backend's body extraction before the existing Docs view rewrites links. Missing
documents fail capture. Keep the generated input revision identical for repeat
comparisons; these states do not freeze a copy of generated documentation.
Instagram cases keep the native gallery under its page container and the fullscreen
zoom owner outside main, as in the application. Six keyed posts use local images
through a managed URL dependency; native subscriptions, image decoding, click,
modal loading and delayed close remain active. Each new case acquires its own
native module code/styles; the shared fixture head and prior cases stay unchanged.
This provider fixture does not
establish live Instagram API acquisition. Grid, opened modal and completed close
are captured at both widths, including native image/gallery DOM retention.
These new cases use ordinary mount motion. Docs sidebar and Instagram caption
hover states remain unverified until the driver performs genuine pointer actions;
synthetic events cannot establish native CSS hover behavior.

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
whose painted appearance is under review. Ordinary geometry cases mount one
same-origin native fixture frame at a time with exact viewport dimensions, collects its
actual DOM and removes it before the next state. Each MCP geometry response
contains one complete fixture, bounding serialization without dropping native
geometry or motion data. Suite bytes/hash and Git head are pinned before browser
work. If the suite bytes change before completion, the runner fails without
`capture.json`; partial case artifacts remain available for diagnosis.
Geometry-only captures park the real pointer on an observed native heading in a
separate band below every fixture. The parent viewport is at least 1440×1100,
or the largest requested frame plus 100 CSS pixels of height; iframe dimensions
remain exactly the case's viewport. Only `visual-driver.html?geometry-parking=1`
fixes its own heading to the bottom using relative-unit CSS. Before and after
readiness/actions, the driver verifies the heading's visible bounds are below
the complete iframe, the heading is `:hover`, the iframe is not, and child
width/height/DPR match the case. A moved pointer or overlap fails capture; no
application hover styles, DOM or event handlers are suppressed. Standalone hover
cases retain their existing top-heading parking and explicit target interaction.

Raster captures use the actual
top-level viewport; both modes retain normal component lifecycles.
Raster mode first awaits native fixture startup readiness, including its owned
focus/scroll actions, before positioning the first tile. It then scrolls the
document from its top through overlapping viewports to its bottom, retaining
post-scroll readiness and exact viewport/scroll bounds for every tile. Each
viewport's PNG and geometry are saved separately. This covers
expanded code and trailing text below the fold without a full-page screenshot
that can change native painting. Reports compare every tile and its scroll
coverage; a missing tile fails. Nested independently scrolling regions still
need explicit suite states for their additional positions. The bounded runner
rejects documents requiring more than 128 tiles.

The `shell-panels` fixture retains the native header, page-root main, full and
sticky footers. User and conditional Settings are main siblings; Contact's lazy
presence host follows both footers, matching the page owner. Wide/narrow cases
capture empty login, registration, Settings, Contact and the shared closed shell.
Open captures first close and reopen their owner; closed captures cycle all four
views and require outgoing User/Contact presence to remain inert until retirement,
with no panel inputs left behind. Native Settings unmounts when closed.

These cases seed only public provider-display flags and an already-ready store
through registered events. Module acquisition, seeded common-content reads,
CSS-variable reads, native subscriptions and presence stay ordinary. Unexpected
HTTP fails the fixture. No credential, provider, contact-submit or settings-write
control is activated. Registration is entered through its existing section event:
the login “Register” button submits Auth. Query-link controller behavior, real
Auth/network completion and message delivery require separate live checks; these
layout/motion cases do not establish them. Settings/Contact sheets load only in
this fixture's own document, without altering the shared head or older cases.

CV skills carousel cases reuse the full native CV page and its actual
`.cv-skills` owner. Wide/narrow captures cover the Digital next slide and Language
previous-wrap slide. Actions use the rendered next/previous/index controls, verify
round trips and wraparound, and require the active heading, content and current
marker to agree while retaining the same carousel/page nodes. These are control,
layout and observed CSS-motion checks; they do not establish touch-drag or genuine
keyboard traversal coverage. The Home sideways image gallery is a different owner.

Comment composer cases extend the existing authenticated comment fixture with
`composer=rich` or `composer=plain`. They click the actual Reply action and retain
its comment card, live preview, lazy composer, mode controls and upload owner.
Registered local form events seed fixed Markdown; native Visual editor/Plain
Markdown controls must preserve canonical text and rendered preview. Cancel must
clear the local reply form and release editor/upload ownership before reopening.
A separate post-draft sentinel must remain unchanged. Case-only transport guards
reject and count unexpected provider/HTTP requests; comment capabilities keep the
asset library disabled. No Submit, upload, library or provider-write control is
activated. These layout/motion checks do not establish trusted keyboard input,
real uploads, publication or Auth behavior.

Native hover cases use the configured Chrome MCP's real `hover` action, including
in geometry-only mode. These cases run serially in the owned top-level tab while
ordinary geometry cases reuse the driver with one iframe response at a time. Before navigation,
the pointer hovers an observed UID for the test driver's neutral heading. The
fixture target must initially be unhovered. Its role/name is matched from the
latest accessibility snapshot and its observed UID is verified against the
checked-in selector in that document before hovering. No UID is inferred from a
selector, and no application node is marked, restyled or replaced. Capture then
requires native `:hover` plus the case's actual sidebar/caption/tooltip result.
The canonical case state records the semantic target and initial-pointer contract,
not ephemeral UIDs; comparison rejects missing interaction evidence even when
geometry matches. Genuine keyboard traversal remains separate from existing
programmatic focus cases.

## Native CSS motion contracts

New captures include `native-css-motion-v2` alongside geometry in both modes.
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
The native stylesheet rules also retain dormant hover/state motion declarations,
conditional scopes, declaration priority, every keyframe definition and native
`@property` registrations (name, syntax, inheritance and initial value). Registrations
control custom-property interpolation and inheritance even when keyframes and
settled pixels stay equal. These remain comparable even when no captured interaction
activates them. This does
not include arbitrary non-motion endpoint declarations in dormant selectors: for
example, an unvisited `:hover` color/transform rule needs a real interaction state
to compare its resulting appearance and native transition keyframes. Actual triggers prove
only the checked-in interactions. Zero-duration changes, easing/delay/keyframe
changes, missing triggers and pseudo changes fail exact comparison even when the
settled geometry and pixels match. Unobservable native start events, inaccessible
stylesheets and embedded/shadow roots needing separate observation are explicit
capture gaps; they cannot be approved as complete observations. Queued native
starts consume one receipt tied to the captured animation and actual DOM target;
a historical path/property match cannot excuse a later missing instance. Add
native interaction cases for states not exercised by the suite.

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
stores bounded Home observation evidence in `#results.dataset.externalBack`
and per-URL Home, Blog, CV and Docs evidence in `dataset.externalBackCases`.
Each route matches the full departing URL; its observer attaches before content
readiness, and return-policy flags are asserted after motion collection. It does
not rewrite application HTML, seed return state or forge navigation timing.
After saving that observation and checking policy, each route measures actual
Search opening/closing `max-height` transitions and compares their full native
effect timing/keyframes with the same controls before departure. A lazy panel
first acquires its normal closed surface. Finite effects captured on native start
remain valid even if absent at the next poll. Frame-height diagnostics accompany
the proof without a minimum frame-count gate; clocks/progress are excluded from
parity. Per-URL records live in `dataset.externalBackInteractions`. Ordinary
navigation follows restoration too; return suppression must preserve later intent.

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

Readonly Neovim states use `/fixtures/neovim-visual.html?visual=patch` or `diff`
through the existing `:neovim-check` target. Compile that target in the same
isolated snapshot as `npm run build`; the latter generates the local repository
workspace consumed by the pinned WASM profile. The static fixture server reuses
Neovim's isolation policy for these documents and `/vendor/neovim/` resources.
`/fixtures/neovim-unsupported.html?visual=unsupported` serves the same fixture
without document isolation, exercising the actual native capability fallback.

The six narrow/wide cases set `standalone: true`, including geometry-only runs:
SharedArrayBuffer/WASM startup must occur in the isolated top-level document.
The runner reuses its one owned tab and awaits an optional fixture-owned
`visualFixture.dispose()` in `finally` before moving to the next document.
Neovim's hook unmounts the real editor and verifies its real worker terminated.
Readiness checks the painted native grid, exact local buffer contents, immutable
buffers, absolute line numbers, equal diff panes and native diff highlighting via
real RPC. It attempts an edit only to assert Neovim rejects it. No worker/grid,
provider read or write is simulated. The native bare `main`, `defpage` boundary
and container, `.github-diff` parent and editor are retained; these component
cases do not exercise GitHub's revision controller,
menu metadata or remote acquisition. Missing approved raster/motion baselines
remain verification gaps; captures never approve them automatically.
