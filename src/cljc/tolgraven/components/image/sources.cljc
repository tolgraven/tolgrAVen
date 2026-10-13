(ns tolgraven.components.image.sources
  "Shared format selection for rendered pictures and server preloads."
  (:require [clojure.string :as string]
            [tolgraven.components.image.eligibility :as eligibility]
            #?(:clj [tolgraven.build.images :refer [responsive-images]]))
  #?(:cljs (:require-macros [tolgraven.build.images :refer [responsive-images]])))

(def responsive (responsive-images))

(defn preview
  "Smallest catalog candidate for a progressive image. Sized local renditions
   enable progressive display unless the catalog explicitly excludes it."
  ([src] (preview src nil))
  ([src asset]
   (if asset
     (let [webp (into {} (map (juxt :width :src)) (get-in asset [:renditions :webp]))
           avif (into {} (map (juxt :width :src)) (get-in asset [:renditions :avif]))
           size (first (sort (filter #(and (< % (:width asset)) (contains? avif %))
                                    (keys webp))))]
       (when size {:width size :webp (get webp size) :avif (get avif size)}))
     (when-let [{:keys [sizes width]} (get responsive (string/replace (or src "") #"^/" ""))]
       (when-let [size (first (sort (filter #(< % width) sizes)))]
         {:width size
          :avif (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "-" size "w.avif"))
          :webp (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "-" size "w.webp"))})))))

(defn progressive?
  ([src] (progressive? src nil))
  ([src asset]
   (and (some? (preview src asset))
        (if asset
          (eligibility/eligible? (get-in asset [:renditions :original]))
          (not= false (:progressive? (get responsive (string/replace (or src "") #"^/" ""))))))))

(defn dimensions
  ([src] (dimensions src nil))
  ([src asset]
   (select-keys (or asset (get responsive (string/replace (or src "") #"^/" "")))
                [:width :height])))

(defn placeholder-background
  "Fixed vertical gradient from codec-owned, validated three-row color samples.
   Invalid or unavailable metadata has no CSS representation."
  ([src] (placeholder-background src nil))
  ([src asset]
   (let [colors (if asset (get-in asset [:placeholder :colors])
                   (get-in responsive [(string/replace (or src "") #"^/" "") :placeholder :colors]))]
    (when (and (vector? colors) (= 3 (count colors))
               (every? #(and (string? %) (re-matches #"^#[0-9a-f]{6}$" %)) colors))
      (str "linear-gradient(to bottom, " (nth colors 0) " 0%, "
           (nth colors 1) " 50%, " (nth colors 2) " 100%)")))))

(defn srcset
  ([src format] (srcset src format nil))
  ([src format asset]
   (if asset
     (when-let [variants (seq (get-in asset [:renditions (keyword format)]))]
       (string/join ", " (map #(str (:src %) " " (:width %) "w") (sort-by :width variants))))
     (when-let [{:keys [sizes width]} (get responsive (string/replace (or src "") #"^/" ""))]
       (string/join ", "
         (concat (for [size sizes]
                   (str (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "-" size "w." format)) " " size "w"))
                 [(str (string/replace src #"(?i)\.(jpe?g|png|webp|avif)$" (str "." format)) " " width "w")]))))))

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
  ([src] (should-use-modern-formats? src nil))
  ([src asset]
   (if asset
     (boolean (and (seq (get-in asset [:renditions :avif]))
                   (seq (get-in asset [:renditions :webp]))))
     (and (string? src)
       (or (converted-avatar? src)
           (and (or (get responsive (string/replace src #"^/" ""))
                    (re-find #"(?i)\.(jpe?g|png)$" src))
                (not (re-find #"^(https?:|//)" src))
                (not (re-find #"\?" src))
                (not (re-find #"avatar" src))
                (not (re-find #"(favicon|android-chrome|apple-touch-icon|mstile)" src))))))))

(defn get-src-variants
  "Get all available format variants for an image path.
   Returns a map with :original, :webp, and :avif paths.
   Useful for preloading or manual format selection."
  ([src] (get-src-variants src nil))
  ([src asset]
   (if asset
     (cond-> {:original (:src asset)}
       (seq (get-in asset [:renditions :webp]))
       (assoc :webp (:src (last (sort-by :width (get-in asset [:renditions :webp])))))
       (seq (get-in asset [:renditions :avif]))
       (assoc :avif (:src (last (sort-by :width (get-in asset [:renditions :avif]))))))
     (if (should-use-modern-formats? src)
       {:original src
        :webp (replace-extension src "webp")
        :avif (replace-extension src "avif")}
       {:original src}))))
