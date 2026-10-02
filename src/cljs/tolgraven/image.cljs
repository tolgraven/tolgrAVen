(ns tolgraven.image
  "Helpers for serving modern image formats (WebP, AVIF) with automatic fallbacks"
  (:require
   [clojure.string :as string]
   [reagent.core :as r]))

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
       (not (re-find #"avatar" src))                      ;; Skip URLs from avatars (for now
       (not (re-find #"\.svg$" src))                      ;; Skip SVG files
       (not (re-find #"(favicon|android-chrome|apple-touch-icon|mstile)" src))))

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

   Browsers select the first supported format. If requesting or decoding that
   source fails, retry the original JPEG/PNG. Call the caller's on-error only when the
   original image fails too."
  [{:keys [src on-error] :as attrs}]
  (r/with-let [*fallback-sources (r/atom #{})]
    (if (should-use-modern-formats? src)
      (let [avif-src (replace-extension src "avif")
            webp-src (replace-extension src "webp")
            ;; Keep failures by original path so a new src tries modern formats anew.
            fallback? (contains? @*fallback-sources src)
            retry! (fn [event]
                     (let [image (.-currentTarget event)
                           current-src (.-currentSrc image)
                           original-src (.-href (js/URL. src (.-baseURI image)))]
                       ;; Safari can select AVIF in Lockdown Mode even though
                       ;; its decoder rejects it. <picture> does not retry itself.
                       (if (and (not fallback?) (not= current-src original-src))
                         (swap! *fallback-sources conj src)
                         (when on-error (on-error event)))))]
        [:picture
         (when-not fallback?
           [:source {:key avif-src :srcSet avif-src :type "image/avif"}])
         (when-not fallback?
           [:source {:key webp-src :srcSet webp-src :type "image/webp"}])
         [:img (assoc attrs :on-error retry!)]])
      ;; No modern format available, just use img directly.
      [:img attrs])))

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

;; For backward compatibility - export main functions
(def ^:export responsiveImage picture)
