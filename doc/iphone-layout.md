# Mobile layout checks

The header, main and footer use the same outer gutter and vertical border width.
The viewport retains Safari's default safe-area containment. Fullscreen overlays,
fixed footer, HUD and back-to-top controls account for exposed safe-area insets.
Keep CSS render-blocking for the first paint and retain matching fallback-font
metrics so hydration cannot change the header geometry.

Check a real iPhone/WebKit build after layout or hydration changes:

1. Cold-load home and a permalink; check heading images, skeleton handoff and
   hydration for flashes, border gaps and changes in header height.
2. Scroll with the menu open/closed, on both sides of 400px, in both orientations
   and color schemes. The upper-right framing must remain opaque.
3. Expand comments; verify snappy two-level reveal, retained parent height and
   explicit folds. Return from an external link with browser Back.
4. Open an image overlay, expand/collapse Safari's toolbar, and check controls
   above the home indicator. Test original-image fallback if AVIF cannot decode.
5. Launch from the Home Screen and verify app icon/name, footer and HUD positioning.

Chromium emulation and passing component tests do not establish WebKit behavior.
