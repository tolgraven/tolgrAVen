(ns tolgraven.components.image.sources
  "Shared format selection for rendered pictures and server preloads."
  (:require [clojure.string :as string]
            [tolgraven.components.image.eligibility :as eligibility]
            #?(:clj [tolgraven.build.images :refer [responsive-images]]))
  #?(:cljs (:require-macros [tolgraven.build.images :refer [responsive-images]])))

(def responsive (responsive-images))

(defn preview
  "Smallest catalog candidate for a progressive image. Sized local renditions
   preserve responsive source selection independently of progressive display."
  [src]
  (when-let [{:keys [sizes width]} (get responsive (string/replace (or src "") #"^/" ""))]
    (when-let [size (first (sort (filter #(< % width) sizes)))]
      {:width size
       :avif (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "-" size "w.avif"))
       :webp (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "-" size "w.webp"))})))

(defn progressive? [src]
  (let [metadata (get responsive (string/replace (or src "") #"^/" ""))]
    (and (some? (preview src))
         (if (contains? metadata :progressive?)
           (:progressive? metadata)
           (eligibility/eligible? metadata)))))

(defn dimensions [src]
  (select-keys (get responsive (string/replace (or src "") #"^/" "")) [:width :height]))

(defn placeholder-background
  "Fixed vertical gradient from codec-owned, validated three-row color samples.
   Invalid or unavailable metadata has no CSS representation."
  [src]
  (let [colors (get-in responsive [(string/replace (or src "") #"^/" "") :placeholder :colors])]
    (when (and (vector? colors) (= 3 (count colors))
               (every? #(and (string? %) (re-matches #"^#[0-9a-f]{6}$" %)) colors))
      (str "linear-gradient(to bottom, " (nth colors 0) " 0%, "
           (nth colors 1) " 50%, " (nth colors 2) " 100%)"))))

(defn srcset [src format]
  (when-let [{:keys [sizes width]} (get responsive (string/replace (or src "") #"^/" ""))]
    (string/join ", "
      (concat (for [size sizes]
                (str (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "-" size "w." format)) " " size "w"))
              [(str (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "." format)) " " width "w")]))))

(defn replace-extension
  "Replace file extension. e.g., 'img/foo.jpg' -> 'img/foo.webp'"
  [path new-ext]
  (string/replace path #"(?i)\.(jpe?g|png|webp|avif)$" (str "." new-ext)))

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
   - Uncatalogued modern formats (WebP, AVIF)"
  [src]
  (and (string? src)
       (or (converted-avatar? src)
           (and (or (get responsive (string/replace src #"^/" ""))
                    (re-find #"(?i)\.(jpe?g|png)$" src))
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
