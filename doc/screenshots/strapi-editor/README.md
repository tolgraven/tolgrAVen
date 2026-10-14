# Inline editor visual evidence

The six comparison sheets show before on the left and after on the right, at
1360 × 900 and 390 × 844 CSS pixels with device scale factor 2. They cover intro,
services, story, moneyshot, interlude, gallery, header, full footer, sticky footer,
post footer, CV, blog heading, docs heading, responsive image and administrator panel.

Baseline: `49d29ce0`. Production component source: `bb3fdc7b`; final CV styles:
`d58f430f`; routing-enabled visual fixture: `980b6638`. The final artifact commit
does not change application source. Animations/video and horizontal galleries were
settled consistently before capture. The header uses the existing history router;
its presence was checked so an error boundary cannot count as visual parity.

All 30 pairs were visually inspected. Layout, text and imagery match. Header pairs
are pixel-identical; the other captures have raster differences of at most 3/255
per color channel. The raw measurements are in `comparisons.json`; these are not
claims that every PNG is byte-identical. Full-resolution PNGs are retained in the
task archive alongside the build logs.

The CV images show a separate container timeline, nested grouping, centered
regular detail editor and narrow creation form. Browser checks created a nested
container through its form, observed its automatic editable child, saved an empty
child's first detail and replaced/saved a logo through the disposable Strapi service.
Opening surfaces began at their measured source bounds and ended at viewport
center; closing retained the parent and returned focus.

Verified: browser regressions (36 tests, 220 assertions), JVM content/editor/SSR
checks (47 tests, 285 assertions), module build contracts, production browser/SSR/
document/return-worker compilation, CMS TypeScript/admin production build and
disposable CMS live configuration/persistence checks.

The browser fixture substitutes sign-in and media conversion/status transport.
Field writes and CV creation use the real disposable CMS HTTP/SQLite service.
Live Supabase sign-in/storage and deployed production cold/SPA/history navigation
were not exercised; compiled renderer fixtures and browser hydration/history
restoration tests cover their component/state contracts.
