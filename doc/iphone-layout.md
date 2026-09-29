# iPhone layout fixes

The `max-width: 400px` layout sets `--line-width-vert` and the outer margin to zero.
The header still reserved `--line-width` (2px) at its right edge. That strip showed
scrolling content because the main element no longer had a border to fill it.
The same mismatch is reproducible through the existing **Line width vert** setting:
the live page computes a 0px main border and a 2px header margin.

The header now reserves the actual vertical border width. Its right-side filler
uses the outer gutter, draws the matching border, and sits above scrolling content.
The footer's gutter extensions use the same outer-margin measurement. This keeps
the framing aligned when inner spacing and outer margins differ.

Related changes:

- Anchor image overlays to all four viewport edges, with safe-area padding on
  their content, instead of sizing them with `100vh` / `99vh`.
- Include the bottom safe area in the fixed footer's height and the back-to-top
  link's offset. Use `svh` for the main page's minimum height, retaining a `vh`
  fallback.
- Load the main stylesheet before first paint in development and production.
  Remove the hard-coded dark inline background; the root uses the theme's colors.
- Make the light/dark theme-color queries mutually exclusive. Link the existing
  favicons and 120px Apple touch icon, and give the existing manifest a name,
  identity, scope, and launch URL.
- Run the CSS build for pull requests. Deploy to CapRover only on a push to
  `master`, so opening a pull request cannot deploy its branch.

## Verification

The CSS build passes. The actual home-template functions were evaluated in both
development and production configurations, with the asset lookup and configuration
adapters stubbed. Checks verified one render-blocking main stylesheet, conditional
theme colors, and icon/manifest links. Manifest image dimensions match the files.

The viewport retains Safari's default safe-area containment. Opting into
`viewport-fit=cover` would also require moving the header and horizontal framing
away from the notch; this change does not opt into that edge-to-edge layout.
Safe-area offsets are kept consistent wherever the browser exposes them: the
footer, back-to-top link, HUD, contact popup, and image overlay. The fullscreen
header filler also uses the configured vertical border width.

Rebased onto the module refactor, retaining its PR validation and master-only
deployment workflow. Copilot setup uses Java 21; its Shadow command inspects the
environment, while application compilation uses the project's Leiningen classpath.
The Firebase redirect-result fix is already included in the new base.

A real iPhone/WebKit check remains useful:

1. Scroll down and back up with the menu closed and open. The upper-right edge
   should remain opaque and align with the main content border.
2. Check widths on both sides of 400px, both orientations, and both color schemes.
3. Open an image overlay and expand/collapse Safari's toolbar.
4. Launch from the Home Screen and check the footer, HUD and back-to-top control
   above the home indicator, along with the app name and icon.
