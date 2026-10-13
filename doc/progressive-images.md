# Progressive image verification

Substantial local images use the existing responsive AVIF/WebP catalog. The shared
eligibility predicate requires an original width above 320, a longest edge of at
least 960, and at least 64 KiB. Explicit catalog/caller overrides take precedence.
Small or inexpensive images retain ordinary responsive selection and one native
image. Original/full-size fallback files remain unchanged.

The catalog contains three oriented image-color samples. SSR paints their fixed
vertical gradient before either image request completes. A different low-resolution
candidate starts with 4rem blur. When a noticeable preview upgrades, its blur clears
in 100ms while its opacity overlaps the sharp final image for 150ms. Cached,
reduced-motion and early/unnoticeable finals settle immediately. The existing crop
and scale remain intact. External document returns use only the appropriate final
candidate; ordinary SPA destinations, including query navigation and query Back,
clear this image policy without restarting the initial document clock.

## Reproduce the image benchmark

Build the baseline and progressive checkouts with their production profiles using
the shared CPU launcher, then run `bb ssr:fixtures` in each checkout. The fixtures
must come from each checkout's current `:ssr` renderer. The baseline for this change
is the updated entrance branch, not the older historical pilot revision.

```sh
python3 scripts/test/image_benchmark.py \
  --baseline /path/to/entrance-checkout \
  --progressive /path/to/progressive-checkout \
  --port 4062 --image-kibps 64 --latency-ms 150 \
  --output target/image-benchmark-results.json
```

Open the displayed localhost URL in Chrome. Set a host browser viewport that fully contains the probe and has the actual
DPR for each case (375/DPR1, 390/DPR2, 1280/DPR1), select that case and click **Run five
pairs**. The harness rejects a mismatched viewport/DPR. Each case retains five
cold-cache runs per version, alternating which version runs first. The probe uses a fixed, fully visible native iframe at the exact case width and
height (375×812, 390×844, 1280×900). The 390/DPR2 case uses the real Retina host
pixel ratio with a 390-wide nested viewport; DPR1 cases use a browser viewport
override. After each case it is replaced with a blank
frame and the shared image link drains before another viewport is selected. Close
standalone screenshot/probe tabs before measuring.

```sh
python3 scripts/test/image_benchmark.py \
  --summarize target/image-benchmark-results.json
```

The summary reports medians and full ranges and rejects overlapping probes,
truncated responses and aborted requests. No setup outlier is silently removed.
Resource metadata includes source revisions, renderer/bootstrap/CSS hashes,
browser user agent and active resource-control policy. Raw data contains only the
authored probe and public hero image information.

## What the metrics mean

Only hero response bodies share the nominal 64 KiB/s link, with 150ms before their
first body chunk and 1024-byte scheduling chunks. Response headers arrive
immediately; CSS, fonts and other assets are unpaced. This is an image-transfer
experiment, not a complete mobile-network simulation.

The probe uses renderer-generated completed SSR markup and native responsive
selection, with application hydration withheld. It records request start, headers,
response end, native load and decode completion separately. RAF sampling records
the first positive decoded-image paint opportunity, first preview opportunity,
first gradient opportunity, first positive final opacity and final settled layer opacity. A progressive final reaches at least .999; the
baseline native image already has its intended .65 design tint and no layer fade. These are observable paint opportunities, not screenshots of every
frame and not LCP. The final settled-opacity metric excludes the outer page entrance and preserves
the existing design tint; raw records include actual/target opacity.

Server/provider acquisition and streamed skeleton latency are excluded so they
cannot be mistaken for image savings. The real live motion driver separately
checks cold streamed SSR, fresh cached SSR, worker-paired returns, hydration and
SPA navigation. The recorded artifact builds used the then-installed one-CPU Docker quota; timing
runs started only after compiler and CPU-probe jobs finished. Current agent builds
use the installed shared launcher policy; record it for any new timing series. Chrome is outside the native
project-Java/Codex-renderer resource monitor. Scheduler/browser variation remains
visible in the retained ranges.

## Visual fixtures

`/probe/progressive/<unique-key>?hold=all` holds both hero images for 15 seconds to
show the initial sampled gradient. `?hold=final` shows the blurred preview while
the larger final candidate is held. These holds are screenshot-only and are never
used by timed cases.

Adding `&snapshot=midfade` pauses the actual CSS opacity/filter transitions at
75ms for a readable screenshot. It postpones the adapter's bounded completion
fallback while paused. This is an explicitly staged visual pose, not evidence of
wall-clock fade duration; the ordinary timing runs and lifecycle tests establish
completion behavior.

`?returns=network` installs an explicitly permitted test-only unload handler;
`?returns=bfcache` leaves normal eligibility intact. Use the external link and
browser Back, then **Show request report**. Inspect actual `pageshow.persisted`,
navigation type, visible layers and new requests rather than assuming which
history mechanism the browser selected. Historical requests from before departure
are retained and distinguished from new return requests.


## Committed image readiness

`image/<picture>` and its aliases accept an optional `:on-visible-ready` callback.
It receives `{:src canonical-source :selected-src native-current-src}` once per
source generation, after the final native image decodes, its progressive overlap
finishes, and two committed paint frames show it in the viewport with visible
ancestors. Ordinary images and SVGs use the same contract. The callback is absent
from SSR and DOM attributes; it does not replace `:on-load` or `:on-error`.

Hidden surfaces wait on the existing visibility observer, scoped ancestor
attribute changes and native motion completion. They do not poll every frame.
Changing source or unmounting cancels the previous acquisition. Consumers should
validate their own upload/preview generation before acting on the notification.

[Recorded protocol-6 measurements, ranges and raw data](benchmarks/progressive-images/README.md).
