# Liquid glass wrapper

`website.components.ui.liquid-glass/<liquid-glass>` is a spec-capable `defc`
with the signature `[spec & forms]`. It adds an independently sized decorative
background behind ordinary Reagent children. Their DOM, focus, input values,
events and opacity are preserved.

```clojure
(:require [website.components.ui.liquid-glass :as glass])

[glass/<liquid-glass>
 {:opacity 0.75
  :outset "1rem"
  :radius "1.5rem"
  :props {:class "account-panel"}
  :content-props {:style {:padding "1rem"}}}
 [<account-summary> account]
 [:button {:on-click #(r/dispatch [:account/edit])} "Edit"]]
```

The standard `:props`, `:classes`, `:features` and container composition remain
available. Root props/ref describe the wrapper; `:content-props` describe its
inner content slot. The wrapper is an inline grid by default; set its dimensions
or display through ordinary root props/classes. It does not clip the children.
Do not put opacity or a filter on a surrounding element merely to change the
glass: doing so can establish a new backdrop root and also fade its content.

## Spec

All keys are optional. The public contract is `glass/spec-schema`, composed with
the shared component instance schema. Numeric options are finite and bounded.

| Key | Default | Meaning |
| --- | --- | --- |
| `:enabled?` | `true` | Enable the decorative layer; children remain mounted when disabled. |
| `:mode` | `:auto` | Chromium SVG refraction when available, or native frosted fallback. `:frosted` forces fallback; `:refraction` explicitly opts into an engine's SVG implementation. |
| `:shape` | `:rounded-rect` | `:rounded-rect`, `:capsule`, `:ellipse`, or `:polygon`. |
| `:points` | A five-sided polygon | 3–32 normalized `[x y]` points in `[0,1]`, used by `:polygon`. Concave polygons use the same even-odd rule for the field and CSS clip. |
| `:radius` | `"1.5rem"` | Rounded rectangle corner radius; capsules and ellipses derive theirs from size. |
| `:bezel-width` | `"0.75rem"` | Refracting border thickness, clamped to half the short side. |
| `:thickness` | `"2.5rem"` | Optical lens depth, separate from layout dimensions. |
| `:profile` | `:squircle` | `:convex`, `:squircle`, `:concave`, `:lip`, or a pure function of normalized edge distance returning a finite height. |
| `:index` | `1.5` | Refractive index, 1–3; 1 gives no refraction. |
| `:scale` | `1` | Refraction strength, 0–4; changing this reuses the map. |
| `:opacity` | `0.85` | Opacity of the glass layer only, 0–1. |
| `:content-opacity` | `1` | Independent opacity of the content slot. |
| `:blur` | `"0.04rem"` | Backdrop blur before refraction; does not regenerate maps. |
| `:saturation`, `:brightness` | `1.2`, `1.05` | Filter multipliers, 0–4; no map regeneration. |
| `:light-angle` | `-60` | Rim-light direction, −180° to 180°. |
| `:light-focus` | `2` | Rim-light falloff, 0.1–8. |
| `:light-opacity` | `0.35` | Rim-light intensity, 0–1; no map regeneration. |
| `:tint` | Translucent white | Any CSS background value. |
| `:border`, `:shadow` | Light border and soft shadow | Ordinary CSS border and box-shadow values. Polygon clipping also clips outer shadows. |
| `:outset` | `"0"` | Relative length or `[top right bottom left]`; positive values extend the background outside the content, negative values shrink it. Percentages are supported. |
| `:content-props` | `{}` | Ordinary Hiccup attrs, classes and styles for the content slot. |
| `:resolution` | `0.5` | Raster samples per measured CSS-pixel coordinate, 0.1–2; independent of device pixel ratio. |
| `:max-pixels` | `65536` | Per-map sample budget, 256–1,048,576. |
| `:max-side` | `1024` | Longest raster side, 16–2048. |
| `:cache?` | `true` | Share immutable maps for matching optical geometry. |
| `:on-error` | None | Optional lifecycle callback receiving an encoding/profile error; the surface falls back and sets `data-glass-error`. |

Optical lengths accept zero or `rem`, `em`, `vw`, `vh`, `vmin`, `vmax` lengths.
Use relative units for authored dimensions; numeric SVG/image coordinates are
derived from browser measurements. Percentage outsets use native CSS layout,
and the actual resulting background dimensions are measured for refraction.
Custom profile functions must be pure. Change the function identity or disable
caching when intentionally changing a function's captured parameters.

`opacity: 1` is still transparent: it composites the tinted, filtered backdrop
at full effect strength, rather than painting an opaque background. The default
tint itself has low alpha. Smaller opacity blends the entire glass treatment
with the scene while leaving its children unchanged.

## Rendering and performance

The optical model follows [Kube's CSS/SVG refraction study](https://kube.io/blog/liquid-glass-css-svg/):
a sampled surface derivative, Snell refraction, a two-channel displacement map
and an angle-dependent rim highlight. The implementation uses the
[SVG displacement equation](https://developer.mozilla.org/en-US/docs/Web/SVG/Reference/Element/feDisplacementMap)
directly: `scale * (channel - .5)`. Its channel correction makes encoded neutral
128 exactly .5, and the filter's scale is twice the maximum field displacement.

- Only the glass background has `backdrop-filter`. Text and interactive children
  are not rasterized, cloned or filtered.
- SSR and the first hydrated render use the same native frosted markup. Native
  hooks measure geometry and acquire maps only after commit and visibility.
- The existing visibility adapter owns intersection observation. ResizeObserver
  measures layout dimensions, avoiding temporary ancestor animation transforms.
  A relative-unit probe catches font changes; measurement is coalesced by a
  cancellable frame callback. Listener ownership uses the existing registry.
- Image generation runs in a cancellable idle task (timer fallback), only when
  optical geometry changes. There is no animation loop, pointer listener,
  scroll polling or recurrent idle timer.
- The cache retains at most 12 maps and 8 MiB of encoded PNG URL text. Native
  raster buffers are not retained. Sample count and longest side are capped even
  for extremely thin or large surfaces. Increasing budgets increases CPU, memory
  and backdrop GPU work; use the defaults unless measurements justify more.
- Leaving view, hiding the document, disabling glass and unmounting release
  measurement/listener work and cancel pending generation. Existing cached maps
  can be reused on return. Effects protect against callbacks arriving after cleanup.
- Strength, opacity, blur, saturation, brightness and light opacity update filter
  properties without regenerating PNGs. Size, radius, bezel, thickness, profile,
  refractive index, polygon geometry and light direction/focus regenerate them.

Rounded rectangles and polygons use signed distance fields; ellipses use a
gradient-normalized distance approximation near the bezel. This is a visual
single-interface lens model, not a full optical renderer. Concave/lip profiles
can sample outside the backdrop's available filter region and have stronger
edge artifacts; convex/squircle are the default-friendly options.

SVG filters in `backdrop-filter` are not interoperable across engines. CSS syntax
acceptance alone cannot establish actual SVG rendering, so `:auto` conservatively
selects Chromium and retains ordinary blur/tint in other engines. The fallback
supports the same layout, shapes, opacity and children, with no image generation.
Its rim treatment is the CSS border/shadow rather than refractive rim lighting.

## Playground and focused checks

The lazy experiments module exposes `/test/liquid-glass`. The playground uses
scoped component subscriptions/events for its controls and does not run on normal
routes. It exercises editable/focusable children, fallback, outsets, shapes,
profiles and multiple matching instances. Existing experiments remain intact.

Agent builds use the CPU launcher. Node tests cover optics, contracts and actual
SSR rendering; the browser suite mounts the real component and verifies content
identity, focus, caching, failure, cleanup and hydration.

```sh
tolgraven-cpu run -- lein with-profile +provided,+project/test run -m shadow.cljs.devtools.cli compile glass-node-test glass-test glass-demo
```

Serve that output from the same source checkout (replace the path with the CPU
launcher's reported snapshot):

```sh
bb test:browser --port 4126 --glass-output /path/to/snapshot/target/glass/public
```

Open `/` for the playground and `/tests/` for the mounted suite. This static
fixture server uses real components; it does not establish application routing
or live service behavior.

`glass-test` and `glass-demo` use isolated `target/glass` output and do not replace
watched application assets. The standalone test driver is
`test/browser/liquid-glass.html`; its script and stylesheet URLs must resolve to
the corresponding isolated build output and current `npm run build` output.
Production consumers import the component's owning namespace and use the normal
shared shell stylesheet, which includes its small reusable layout rules.
