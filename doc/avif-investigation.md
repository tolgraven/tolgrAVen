# AVIF deployment investigation — 2026-10-02

Investigated `master` at `8980a71cbe7d788972ea7f3215c3a228a9c87803` against
https://web.bux.tolgraven.se/ and a local build of the repository Dockerfile.
The pre-existing Dockerfile change (`/root/.m2` -> `/root/m2`) was preserved.

## Confirmed findings

- All **59 tracked AVIF files** were retrievable from production and matched the
  repository bytes exactly. ImageMagick decoded all of them successfully.
  Three additional local, untracked avatar AVIFs returned 404 remotely; the image
  component explicitly excludes avatars from modern-format substitution.
- Production and Docker both return **`application/octet-stream` for AVIF**.
  Ring 1.15.3's default MIME table contains WebP but lacks AVIF. Optimus preserves
  binary bytes and does not set the content type, so the outer Ring middleware
  supplies the generic fallback. An explicit AVIF mapping fixes this.
- The fresh Docker build served all **59/59 tracked AVIFs successfully to
  Chromium's image decoder**, even with the original generic MIME type.
  Consequently, the MIME bug alone does **not** explain an intermittent invisible
  image in this browser.
- Homepage/Story images decoded and rendered locally. The Story images initially
  have zero visual dimensions because `.appear-wrapper.zoom` uses `scale(0)` and
  zero opacity; navigating/scrolling into view adds `appeared` and displays them.
  This did not remain stuck during the local checks. CV detail images also have
  intentional `display: none` styling in collapsed cards.
- The inspected production CSS was byte-identical to the earlier local Docker
  image's CSS. Production JavaScript contains the image/video interlude branch
  from the recently merged media fix (#44).
- The public host presented an expired TLS certificate. Normal curl verification
  and the in-app browser failed (`ERR_CERT_DATE_INVALID`). Public asset responses
  were inspected with certificate verification disabled only for those diagnostic
  curl requests. Live browser rendering was therefore **not verified**.

## Reproduction

```sh
docker build --progress=plain -t tolgraven-avif-investigation .
docker run -d --name tolgraven-avif-master \
  -e STAGE=true -p 127.0.0.1:3302:3000 tolgraven-avif-investigation
curl -I http://localhost:3302/img/tolgrav.avif
```

`STAGE=true` permits local HTTP without the production HTTPS redirect; it retains
production asset optimization and frozen asset serving. No production secrets
were supplied. The original Dockerfile build succeeded, including the Shadow
release build. Codox emitted documentation warnings but completed.

The built baseline image is
`sha256:651c4f513a9b1458f6c85f90f9ef651ae29b98da280ea687ee60f52c2f929b48`.
It was built before the MIME fix was applied and intentionally retains the bug.

## Change and validation

`src/clj/tolgraven/middleware.clj` now passes
`{:mime-types {"avif" "image/avif"}}` to `wrap-content-type`.

`test/clj/tolgraven/media_response_test.clj` exercises production Optimus serving
through the application middleware, checking AVIF/WebP status, MIME type and
exact binary contents. The test fails on the original AVIF header and passes
with the changed middleware (six assertions). The updated source and test were
loaded in a separate JVM using the Docker-built uberjar; the baseline server
was not altered by that test. No deployment was performed.

## Remaining uncertainty

The intermittent "loaded but never displays" symptom was not reproduced in
Chromium. The affected browser/version and a specific failing image or page
state are still needed to distinguish browser-specific decoding, an animation
or stacking issue, and a transient request failure. Correcting the MIME header
and the TLS certificate addresses two verified defects, but neither has been
proved to account for the entire reported symptom.

The picture component chooses AVIF based on format support and has no error
handler to retry WebP/original after an AVIF request or decode failure. This is
an additional resilience gap, not a demonstrated cause of this incident.
