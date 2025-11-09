# IMAGES
## Modern format support (WebP & AVIF)

All JPG/PNG images are automatically converted to WebP and AVIF for better performance.
WebP averages 46% smaller, AVIF can be 50-70% smaller depending on image type.

### Usage in ClojureScript

Use the `tolgraven.image` namespace for automatic format selection:

```clojure
(ns my-app.views
  (:require [tolgraven.image :as img]))

;; Drop-in replacement for [:img]
[img/picture {:src "img/photo.jpg" :alt "My photo"}]

;; For background images (adds .media-as-bg class)
[img/media-as-bg {:src "img/background.jpg" :alt "Background"}]
```

The component generates HTML5 `<picture>` elements with proper source sets:

```html
<picture>
  <source srcset="img/photo.avif" type="image/avif">
  <source srcset="img/photo.webp" type="image/webp">
  <img src="img/photo.jpg" alt="My photo">
</picture>
```

Browsers automatically pick the first format they support. No JavaScript required.

### Adding new images

```bash
# 1. Add JPG/PNG to resources/public/img/
cp new-photo.jpg resources/public/img/

# 2. Convert to modern formats
./scripts/convert-images.sh

# 3. Use in code
[img/picture {:src "img/new-photo.jpg" :alt "Description"}]
```

The script skips favicons and already-converted files. Use `--force` to reconvert everything.

### Verification

```bash
./scripts/verify-images.sh
```

Shows conversion status and size savings for sample images.

### Migration

Replace existing image tags:

```clojure
;; Before
[:img {:src "img/photo.jpg" :alt "Photo"}]

;; After
[img/picture {:src "img/photo.jpg" :alt "Photo"}]
```

Everything else stays the same. Original images are kept as fallbacks for older browsers.

### Utility functions

```clojure
;; Get all format variants
(img/get-src-variants "img/photo.jpg")
;; => {:original "img/photo.jpg"
;;     :webp "img/photo.webp"
;;     :avif "img/photo.avif"}
```

Useful for preloading critical images with the proper format.

### Browser support

- Chrome/Firefox/Safari 16+: AVIF (smallest)
- Safari 14-15: WebP (smaller)
- Older browsers: Original (fallback)

97%+ of users get optimized images.

### Middleware setup

Already configured in `middleware.clj`:
- Asset pipeline includes WebP/AVIF (line 71-72)
- Optimization skips modern formats (line 83-85)
- Gzip correctly skips images
- Last-Modified headers for caching

### Performance tips

- Use `loading="lazy"` for below-fold images
- Preload critical above-fold images
- Always provide meaningful alt text
- Keep originals (they're the fallback)

### File structure

For each image, three versions exist:

```
resources/public/img/
  ├── photo.jpg          # Original (fallback)
  ├── photo.webp         # WebP version
  └── photo.avif         # AVIF version
```

Never delete the originals - older browsers need them.

### Quality settings

- WebP: 85% quality, max compression effort
- AVIF: 80% quality (achieves better quality at lower settings)
- Originals: Progressive JPEGs for faster perceived load

### Troubleshooting

**Images not displaying?**
- Check WebP/AVIF files exist alongside originals
- Verify file permissions match originals
- Check browser console for 404s

**Conversion failed?**
- Install ImageMagick: `brew install imagemagick`
- Install libwebp: `brew install webp`
- Check ImageMagick AVIF support: `magick identify -list format | grep AVIF`

**Need to exclude certain images?**
Edit `scripts/convert-images.sh` and add patterns to the skip condition.
