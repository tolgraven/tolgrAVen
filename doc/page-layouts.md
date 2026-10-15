# Pages and section layout in Strapi

**Site features and pages → Layouts** contains ordered, repeatable pages. Each
page has a stable identity, optional path, width/outline switches, responsive
column counts, and ordered sections. **Publish site changes** uses the existing
build snapshot: browser and server routing, SSR and rendered configuration change
together after deployment. Saving configuration alone does not deploy the site.

The first upgrade seeds Home and Experiments only once. Existing content single
types are preserved. Later restarts retain edited layouts, including deliberately
empty section lists. Home uses the `home` identity; the experiments tab uses
`experiments` (also routed at `/experiments/layout`). Keep those identities while their host routes are enabled.

## Adding sections

Each section selects a component from the generated picker. Native Home sections
and every enabled module exporting `:view :view` appear automatically. A module
without a default view remains a dedicated page or internal feature. Disabled
modules are omitted from the published renderer without deleting stored layouts.
The picker is refreshed during provisioning; adding a new code module still needs
a site build and CMS provisioning before it can be selected.

Give each section an identity unique within its page. Optional labels appear in
**Page contents** indexes. `page-index` renders native links to the other sections;
place it in an `aside` region for a contents menu. Existing feature modules retain
their own content, initialization, state and loading/error boundaries. Repeating
a stateful module shares that module's state and content; this is not a separate
account/configuration instance.

A `story` section can supply its own **Content** JSON with `heading`, `title`,
`text` and `images`, using the same shape as the existing Home story. Omit Content
to reuse Home's managed story. The seeded experiment contains different text and
an empty image list; this content belongs to that section and never overwrites
the `story` single type. It is edited in Strapi and published with layout config;
it does not participate in the site's inline field editor.

## Width, outlines and responsive grids

Without Columns, sections keep their original vertical Home layout. With Columns,
sections use a grid. Span selects 1–12 columns, clamped to the current column
count. Wide sections span the grid and remove their own horizontal section inset.
Page Wide removes the grid's extra outer inset. Outline can remove the page's
outer outline or an individual section's outline. Existing site chrome remains
owned by the shared shell.

Columns is a mobile-first JSON map:

```json
{"mobile": 1, "tablet": 2, "small-monitor": 3, "large-monitor": 4}
```

Omitted larger sizes inherit the nearest smaller value. Existing thresholds are
560, 992 and 1200 CSS pixels. CSS gives SSR/cold loading the same grid before JS;
the shared breaking-point subscription updates live layouts after boot. Code can
reuse the same inheritance without defining more media queries:

```clojure
@(r/subscribe [:layout/responsive {:mobile 1, :tablet 2, :large-monitor 4} 1])
```

This returns nil before browser dimensions exist. Keep first-render DOM structure
stable; use it for layout values and use CSS for initial geometry. Import
`website.breakpoints` when consuming it outside the ordinary boot graph. Boot owns
the existing debounced resize listener; importing components installs no listener.

## New pages and limits

An optional path such as `/studio` creates a native Reitit route named
`:layout-studio` for a page whose identity is `studio`. It uses the same generic
Home module page and SSR renderer. Paths cannot claim `/`, `/about`, `/services`,
`/hire`, `/blog`, `/cv`, `/docs`, `/test`, `/neovim`, `/api`, `/log`, or OAuth routes
and their child paths. Dedicated module pages retain their existing declarations.
Page/section identities, paths, component choices, counts and grid values are
validated before publication. Unknown components fail; unavailable modules are
filtered by the site's compiled capability selection.

The page and section schemas are in `website.page-layout`. Strapi's repeatable
`layout.page` and `layout.section` inputs project only those public fields;
component IDs, private config and webhook credentials never enter layout output.
