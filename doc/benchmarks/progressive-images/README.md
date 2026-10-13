# Progressive image measurements

The desktop preview became visible about 2.0 seconds earlier under this controlled
image-only slow link. It added 7,853 response-body bytes and about 129 ms to final
decode, followed by the requested 150 ms opacity overlap. A small DPR1 device chose
one 400-wide image in both versions; it received no extra preview request or fade.

These are five paired cold-cache runs per viewport and version, 30 samples total.
All completed samples are retained. Numbers are medians with the full observed
range in parentheses, in milliseconds. This is an image-transfer comparison of
completed SSR markup with hydration withheld, not LCP or backend startup timing.

## Image bytes and painted readiness

| Viewport / DPR | Version | Body bytes / requests | First decoded image opportunity | Final decode | Settled final layer |
| --- | --- | ---: | ---: | ---: | ---: |
| 375 / DPR1 | baseline | 7,853 / 1 | 335.0 (330.0–372.2) | 289.4 (286.5–296.0) | 335.1 (330.2–372.3) |
| 375 / DPR1 | progressive | 7,853 / 1 | 294.1 (292.5–304.1) | 285.1 (280.8–293.1) | 294.2 (292.5–304.2) |
| 390 / DPR2 | baseline | 34,371 / 1 | 756.6 (740.8–798.7) | 709.8 (701.3–746.3) | 756.8 (740.9–798.7) |
| 390 / DPR2 | progressive | 42,224 / 2 | 465.1 (437.0–481.0) | 824.2 (818.7–825.1) | 1007.0 (997.6–1027.5) |
| 1280 / DPR1 | baseline | 146,118 / 1 | 2472.1 (2461.3–2541.0) | 2423.7 (2422.0–2493.1) | 2472.2 (2461.4–2541.1) |
| 1280 / DPR1 | progressive | 153,971 / 2 | 466.1 (446.0–510.3) | 2552.2 (2546.1–2592.7) | 2732.7 (2724.0–2785.5) |

The selected final source was exactly 400w AVIF at 375/DPR1, 800w AVIF at
390/DPR2, and original-width AVIF at 1280/DPR1 in every run. Body sizes were
7,853, 34,371 and 146,118 bytes respectively. Different-source previews used
the 7,853-byte 400w AVIF concurrently with the final. Server logs verified distinct
requests, exact body bytes sent, no aborted responses and no overlapping probes.

## Separate network, decode and display clocks

Times below are medians from navigation start. “Final request” is the browser’s
Resource Timing requestStart; “end” is responseEnd. Each raw run also retains
resource discovery, headers, server request/body clocks and all ranges.

| Viewport / DPR | Version | Final request | Final response end | Final load | Final decode | First positive final | Settled final |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 375 / DPR1 | baseline | 14.8 | 284.6 | 285.2 | 289.4 | 335.0 | 335.1 |
| 375 / DPR1 | progressive | 12.6 | 281.9 | 282.7 | 285.1 | 294.1 | 294.2 |
| 390 / DPR2 | baseline | 15.0 | 690.4 | 694.3 | 709.8 | 756.7 | 756.8 |
| 390 / DPR2 | progressive | 14.0 | 810.2 | 811.4 | 824.2 | 867.0 | 1007.0 |
| 1280 / DPR1 | baseline | 16.4 | 2394.2 | 2395.2 | 2423.7 | 2472.1 | 2472.2 |
| 1280 / DPR1 | progressive | 21.9 | 2520.7 | 2522.2 | 2552.2 | 2597.0 | 2732.7 |

Progressive first-preview medians were 465.0 ms at 390/DPR2 and 466.0 ms at
1280/DPR1. The sampled gradient was already available at median 87.0, 83.8
and 96.9 ms for the three progressive viewport cases. At 375/DPR1 the same-source
preview was suppressed; the first image was the sole final image.

The desktop final decode difference was 128.5 ms, close to transferring the extra
7,853 bytes at 64 KiB/s (119.8 ms). The final request itself started at median
21.9 ms rather than 16.4 ms. There was no multi-second delay in scheduling the
final request in this sample. The perceived blurred interval was about 2.27 seconds
because the preview appeared at 466 ms while the settled sharp layer arrived at
2,733 ms. That explains a plausible perception of a longer wait without claiming
it establishes the cause of every live-network report. At 390/DPR2 the final decode
difference was 114.4 ms; the preview appeared about 292 ms earlier. Extra bytes
were 22.8% at 390/DPR2 and 5.4% on desktop. The 375/DPR1 difference should not
be interpreted as a progressive-preview gain: both versions fetched the same image.

## Protocol and limits

- Baseline: `cd9fa9ea5a9f6f9213a8be58a12bfc5a007c9bf6` (updated entrance branch).
- Progressive: `9c4b53206e6ddfd4470731abb5a6af2e20a80c4e`. Later readiness-callback
  work is opt-in and does not participate in this hero timing fixture.
- Hero bodies only share 64 KiB/s, 150 ms before the first body chunk, 1,024-byte
  pacing chunks; headers, CSS, fonts and other assets are unpaced.
- Fixed, physically visible probes: 375×812/DPR1, 390×844/DPR2, 1280×900/DPR1.
  Chrome 155 on macOS; actual user-agent, source and output hashes are in the raw metadata.
- Five pairs alternate version order; unique no-store URLs, blank-frame/idle guard
  between cases. No warm-up or completed outlier was silently removed.
- Artifact builds used the then-installed one-CPU Docker quota, 2 GiB memory and
  1 GiB JVM heap. No compiler or synthetic CPU probe ran during timing. The native
  project-Java/Codex-renderer aggregate monitor allowed 1.5 cores; Chrome was outside
  its scope. The scrubbed end snapshot records Docker quota zero and system load.
  The subsequently installed two-slot/1.5-CPU-per-build policy is a separate environment.
- Browser RAF samples decoded paint opportunities and actual opacity, not physical
  screenshots of every frame. Baseline settled opacity preserves its native .65
  design tint; progressive inner-final target is 1. Outer tint and page entrance
  are excluded. Five pairs and retained ranges do not establish a statistical guarantee.
- Server/provider and streamed skeleton latency are excluded. Cold/cached SSR,
  worker-paired restore, hydration and SPA history have separate live behavior checks.

An initial protocol-6 setup failed before producing a completed sample: the
baseline’s intentional .65 opacity never met an incorrect .999 completion test.
The corrected protocol measures each native layer’s actual settled contribution.
The subsequent series completed all 30 samples. Historical pilot data is retained
separately because viewport changes/offscreen probes allowed concurrent image
work on the shared link. Historical protocol-2 runs preceded the final requirements.
Neither archive is evidence for the current timing claim.

## Evidence and reproduction

- [All 30 protocol-6 runs](protocol-6-runs.json)
- [Summary with full ranges](protocol-6-summary.json)
- [End-of-series resource snapshot](protocol-6-environment-end.json)
- [Historical contaminated pilot](historical-contaminated-pilot.json)
- [Historical protocol-2 runs](historical-protocol-2-runs.json)
- [Executable harness and visual fixture instructions](../../progressive-images.md)

Visual evidence in `doc/screenshots/` shows the generated gradient with both images
pending, the 4rem preview, a deliberately paused 75 ms overlap pose, the sharp final
and the faithful entrance baseline before hydration. The paused pose is explicitly
visual evidence; ordinary timed cases and lifecycle tests establish fade duration.
