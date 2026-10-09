(ns tolgraven.components.image.sources
  "Shared format selection for rendered pictures and server preloads."
  (:require [clojure.string :as string]))

(defn replace-extension
  "Replace file extension. e.g., 'img/foo.jpg' -> 'img/foo.webp'"
  [path new-ext]
  (string/replace path #"(?i)\.(jpe?g|png)$" (str "." new-ext)))

(defn- converted-avatar? [src]
  ;; This versioned object layout is published only after PNG/WebP/AVIF exist.
  ;; Legacy avatars and arbitrary external URLs retain their original behavior.
  (boolean (re-matches #"https?://[^/?#]+/storage/v1/object/public/avatars/[A-Za-z0-9_-]+/[a-f0-9]{64}\.png" src)))

(defn should-use-modern-formats?
  "Determine if we should generate modern format sources for this image.
   Skip for:
   - SVG files (already vector-based)
   - External URLs other than our versioned, converted Supabase avatars
   - URLs with query strings (likely already proxied/optimized)
   - Favicons and app icons
   - Already modern formats (webp, avif)"
  [src]
  (and (string? src)
       (or (converted-avatar? src)
           (and (re-find #"(?i)\.(jpe?g|png)$" src)
                (not (re-find #"^(https?:|//)" src))
                (not (re-find #"\?" src))
                (not (re-find #"avatar" src))
                (not (re-find #"(favicon|android-chrome|apple-touch-icon|mstile)" src))))))

(defn get-src-variants
  "Get all available format variants for an image path.
   Returns a map with :original, :webp, and :avif paths.
   Useful for preloading or manual format selection."
  [src]
  (if (should-use-modern-formats? src)
    {:original src
     :webp (replace-extension src "webp")
     :avif (replace-extension src "avif")}
    {:original src}))
