(ns tolgraven.image
  "Helpers for serving modern image formats (WebP, AVIF) with automatic fallbacks"
  (:require
   [clojure.string :as string]))

(defn- replace-extension
  "Replace file extension. e.g., 'img/foo.jpg' -> 'img/foo.webp'"
  [path new-ext]
  (string/replace path #"\.(jpe?g|png)$" (str "." new-ext)))

(defn- should-use-modern-formats?
  "Determine if we should generate modern format sources for this image.
   Skip for:
   - SVG files (already vector-based)
   - External URLs (we don't control those assets)
   - URLs with query strings (likely already proxied/optimized)
   - Favicons and app icons
   - Already modern formats (webp, avif)"
  [src]
  (and (string? src)
       (re-find #"\.(jpe?g|png)$" src)
       (not (re-find #"^(https?:|//)" src))               ;; Skip external URLs
       (not (re-find #"\?" src))                          ;; Skip URLs with query strings
       (not (re-find #"\.svg$" src))                      ;; Skip SVG files
       (not (re-find #"(favicon|android-chrome|apple-touch-icon|mstile)" src))))

(defn picture
  "Generate a <picture> element with WebP and AVIF sources and fallback to original.

   Usage:
     [picture {:src 'img/photo.jpg' :alt 'My photo'}]
     [picture {:src 'img/photo.jpg' :alt 'My photo' :class 'hero-img'}]

   Generates:
     <picture>
       <source srcset='img/photo.avif' type='image/avif'>
       <source srcset='img/photo.webp' type='image/webp'>
       <img src='img/photo.jpg' alt='My photo' class='hero-img'>
     </picture>

   Browsers automatically select the first format they support."
  [{:keys [src] :as attrs}]
  (if (should-use-modern-formats? src)
    [:picture
     [:source {:srcSet (replace-extension src "avif")
               :type "image/avif"}]
     [:source {:srcSet (replace-extension src "webp")
               :type "image/webp"}]
     [:img attrs]]
    ;; No modern format available, just use img directly
    [:img attrs]))

(defn img
  "Smart img component that automatically uses modern formats when available.
   Alias for picture component for drop-in replacement."
  [attrs]
  [picture attrs])

(defn media-as-bg
  "Generate picture element optimized for use as background media.
   Adds common background styling attributes."
  [{:keys [src alt class] :as attrs}]
  (let [combined-attrs (merge attrs
                              {:class (str "media media-as-bg " (or class ""))})]
    [picture combined-attrs]))

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

;; For backward compatibility - export main functions
(def ^:export responsiveImage picture)
