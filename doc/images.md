# IMAGES & VIDEOS
## Modern format support

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

---

# VIDEOS
## Modern format support (VP9 & AV1 WebM)

Videos can be converted to modern WebM formats for better compression and streaming.

### Usage in ClojureScript

Use the `tolgraven.video` namespace for automatic format selection:

```clojure
(ns my-app.views
  (:require [tolgraven.video :as vid]))

;; Basic video with modern format sources
[vid/video {:src "media/clip.mp4"
            :loop true
            :muted true
            :autoplay true}]

;; Video with standard poster (uses JPG)
[vid/video {:src "media/clip.mp4"
            :poster "media/clip.jpg"
            :controls true}]

;; Video with OPTIMIZED poster (uses WebP/AVIF!)
[vid/video-with-picture-poster
  {:src "media/clip.mp4" :loop true :muted true}
  {:poster "media/clip.jpg" :alt "Preview"}]

;; Auto-infer poster from video name (media/clip.mp4 → media/clip.jpg)
[vid/video-with-auto-poster {:src "media/fog-3d-small.mp4"
                             :loop true
                             :muted true}]
```

The component generates HTML5 `<video>` elements with multiple source options:

```html
<video loop muted autoplay>
  <source src="media/clip-av1.webm" type="video/webm; codecs=av01.0.05M.08">
  <source src="media/clip-vp9.webm" type="video/webm; codecs=vp9">
  <source src="media/clip.mp4" type="video/mp4">
</video>
```

Browsers automatically select the first format they support.

### Converting videos

```bash
# Convert MP4 videos to WebM formats
./scripts/convert-videos.sh

# Force reconvert all videos
./scripts/convert-videos.sh --force
```

Creates two WebM versions:
- VP9 WebM: Good compression, 95%+ browser support
- AV1 WebM: Best compression, 73%+ browser support

**Note**: Results vary based on source quality. Already-optimized H.264 videos may see modest or no size reduction. AV1 typically performs better than VP9 for file size.

### File structure

For each video, three versions exist:

```
resources/public/media/
  ├── clip.mp4           # Original H.264 (fallback)
  ├── clip-vp9.webm      # VP9 version
  └── clip-av1.webm      # AV1 version
```

### Browser support

- Chrome 90+, Firefox 93+, Edge 90+: AV1 (best compression)
- Chrome, Firefox, Edge, Safari 14.1+: VP9 (good compression)
- All browsers: H.264 MP4 (universal fallback)

### Poster Images

Poster images (the preview shown before video plays) can use modern formats too!

**Problem**: HTML5 video's `poster` attribute only accepts one URL, so you can't use `<picture>` element directly.

**Solution**: Use `video-with-picture-poster` which overlays an optimized `<picture>` element:

```clojure
;; Poster uses WebP/AVIF automatically!
[vid/video-with-picture-poster
  {:src "media/video.mp4" :loop true :muted true}
  {:poster "media/video.jpg" :alt "Video preview"}]

;; Or auto-infer poster path (video.mp4 → video.jpg)
[vid/video-with-auto-poster {:src "media/video.mp4" :loop true}]
```

The poster is positioned absolutely over the video and hidden when playback starts.

**Results**: Poster images get the same 50-64% size reduction as other images!
- `fog-3d-small.jpg`: 34KB → 12KB WebP (65% smaller)
- `nihil-shoot2.jpg`: 50KB → 18KB WebP (64% smaller)

### Utility functions

```clojure
;; Get all format variants
(vid/get-src-variants "media/clip.mp4")
;; => {:original "media/clip.mp4"
;;     :vp9 "media/clip-vp9.webm"
;;     :av1 "media/clip-av1.webm"}

;; Infer poster path from video
(vid/infer-poster "media/video.mp4")
;; => "media/video.jpg"

;; Generate preload links
[vid/preload-link "media/hero-video.mp4"]
```

### Encoding settings

Current settings (in `convert-videos.sh`):
- VP9: CRF 30, cpu-used 2 (balanced speed/quality)
- AV1: CRF 35, preset 6 (fast encoding, good quality)

These work well for background/decorative videos. For higher quality needs, lower the CRF values (18-28).

### Performance tips

- Use `muted` and `autoplay` for background videos
- Add `loading="lazy"` for below-fold videos
- **Use `video-with-auto-poster`** - automatically uses optimized poster images
- Provide poster images for better perceived load time (saved alongside video as .jpg)
- Keep videos short (< 10 seconds) for background use
- Consider WebM-only for decorative content (with fallback)
- Name poster images the same as videos: `video.mp4` + `video.jpg` = auto-detected

### Troubleshooting

**Videos not playing?**
- Check WebM files exist alongside originals
- Verify file permissions match originals
- Test in multiple browsers (Safari needs 14.1+ for VP9)
- Check browser console for codec errors

**Conversion failed?**
- Install ffmpeg: `brew install ffmpeg`
- Check codec support: `ffmpeg -codecs | grep -E "(vp9|av1)"`
- AV1 encoding is slow - be patient or use lower preset

**File sizes larger?**
If modern formats are larger than original:
- Source is already highly optimized H.264
- Try lower CRF values (lower = better quality/larger)
- For background videos, try CRF 40-45 for aggressive compression
- Test if AV1 provides better results than VP9

### Middleware setup

Already configured in `middleware.clj`:
- Asset pipeline includes WebM files (line 72)
- Gzip correctly skips video files
- Partial content support for Safari video playback
