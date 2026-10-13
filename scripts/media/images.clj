(ns media.images
  "Bundled and uploaded image conversion share these codecs and publication rules."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def formats ["webp" "avif"])
(defn original? [file]
  (and (#{"jpg" "jpeg" "png"} (some-> (fs/extension file) str/lower-case))
       (not (re-find #"favicon|android-chrome|apple-touch-icon|mstile" (str file)))))

(defn variant [file format]
  (fs/path (str (fs/strip-ext file) "." format)))

(defn originals []
  (filter #(and (fs/regular-file? %) (original? %))
          (fs/glob "resources/public" "**" {:hidden true})))

(defn convert! [file force?]
  (when-not (and (original? file) (fs/regular-file? file))
    (throw (ex-info (str "Missing or unsupported image: " file) {})))
  (frequencies
    (for [format formats
          :let [target (variant file format)]]
      (if (and (not force?) (fs/exists? target)
               (not (pos? (compare (fs/last-modified-time file) (fs/last-modified-time target)))))
        :skipped
        (let [temporary (fs/create-temp-file {:dir (or (fs/parent target) ".")
                                               :prefix (str (fs/file-name target) ".")})]
          (try
            (apply process/shell
              (if (= format "webp")
                ["cwebp" "-quiet" "-q" "85" "-m" "6" (str file) "-o" (str temporary)]
                [(str (or (fs/which "magick") (fs/which "convert")
                          (throw (ex-info "ImageMagick is required" {}))))
                 (str file) "-quality" "80" (str "avif:" temporary)]))
            (fs/set-posix-file-permissions temporary "rw-r--r--")
            (fs/move temporary target {:replace-existing true :atomic-move true})
            :converted
            (finally (fs/delete-if-exists temporary))))))))

(defn arguments [args]
  (loop [[arg & more :as remaining] args force? false]
    (cond
      (= "--force" arg) (recur more true)
      (= "--" arg) {:force? force? :files more}
      (and arg (str/starts-with? arg "-")) (throw (ex-info "Use [--force] [--] [file ...]" {}))
      :else {:force? force? :files remaining})))

(defn -main [& args]
  (let [{:keys [force? files]} (arguments args)
        counts (reduce #(merge-with + %1 (convert! %2 force?)) {} (or (seq files) (originals)))]
    (println "Image conversion:" (get counts :converted 0) "variants created,"
             (get counts :skipped 0) "current variants skipped.")))

(defn verify! []
  (let [missing (for [file (originals), format formats
                      :when (not (fs/regular-file? (variant file format)))]
                  (variant file format))]
    (doseq [file missing] (println "Missing:" (str file)))
    (when (seq missing) (throw (ex-info "Missing image variants; run bb images" {:count (count missing)})))
    (println "All public image variants are present.")))

(def responsive-widths [80 160 320 640 960 1280 1920 2560])
(def responsive-min-dimension 960)
(def responsive-min-bytes (* 64 1024))

(defn responsive-original? [file]
  (and (or (original? file)
           (#{"webp" "avif"} (some-> (fs/extension file) str/lower-case)))
       (not (re-find #"(?i)(?:^|/)(?:uploads|avatars)(?:/|$)|avatar|favicon|android-chrome|apple-touch-icon|mstile|-\d+w\.[^.]+$" (str file)))))

(defn responsive-eligible? [{:keys [width height bytes frames]}]
  (and (= 1 frames)
       (pos-int? height)
       (pos-int? width)
       (nat-int? bytes)
       (< 320 width)
       (<= responsive-min-dimension (max width height))
       (<= responsive-min-bytes bytes)))

(defn responsive-sizes [width]
  (filterv #(< % width) responsive-widths))

(defn responsive-catalog
  "Refresh discovered entries while retaining manually curated sizes and opt-outs.
   Metadata is keyed by the same public-relative source path as the runtime catalog."
  [catalog metadata]
  (into (sorted-map)
    (concat
      (for [[src entry] catalog
            :when (or (not (:generated? entry)) (:exclude? entry)
                      (some-> (get metadata src) responsive-eligible?))]
        [src (let [data (get metadata src)
                   entry' (cond-> entry
                            (and (not (:exclude? entry)) (:bytes data)) (assoc :bytes (:bytes data)))]
               (if (and (:generated? entry') (not (:exclude? entry')))
                 (let [{:keys [width height]} data]
                   (assoc entry' :width width :height height :sizes (responsive-sizes width)))
                 entry'))])
      (for [[src {:keys [width height] :as data}] metadata
            :when (and (not (contains? catalog src)) (responsive-eligible? data))]
        [src {:width width
              :height height
              :bytes (:bytes data)
              :sizes (responsive-sizes width)
              :generated? true}]))))

(defn imagemagick []
  (str (or (fs/which "magick") (fs/which "convert")
           (throw (ex-info "ImageMagick is required" {})))))

(defn band-colors
  "Three horizontal RGB bands from a row-major 3×3 sample. No CSS or URLs."
  [pixels]
  (when-not (= 9 (count pixels))
    (throw (ex-info "Invalid placeholder sample" {})))
  (let [bands (mapv (fn [row]
                      (apply format "#%02x%02x%02x"
                             (map #(long (Math/round (/ (double (reduce + %)) 3)))
                                  (apply map vector row))))
                    (partition 3 pixels))]
    bands))

(defn placeholder! [file]
  (let [output (:out (process/shell {:out :string :extra-env {"MAGICK_THREAD_LIMIT" "1"}}
                                   (imagemagick) "-limit" "thread" "1" (str file)
                                   "-auto-orient" "-background" "white" "-alpha" "remove" "-alpha" "off"
                                   "-colorspace" "sRGB" "-resize" "3x3!" "-depth" "8" "-type" "TrueColor" "txt:-"))
        pixels (mapv (fn [[_ hex]]
                       (mapv #(Long/parseLong (subs hex % (+ % 2)) 16) [0 2 4]))
                     (re-seq #"#([0-9a-fA-F]{6})(?:[0-9a-fA-F]{2})?\s" output))]
    {:colors (band-colors pixels)}))

(defn catalog-placeholders! [catalog]
  (into (sorted-map)
    (map (fn [[src entry]]
           [src (if (:exclude? entry) entry
                    (assoc entry :placeholder (placeholder! (fs/path "resources/public" src))))]))
    catalog))

(defn animated-png? [file]
  ;; APNG declares acTL before its first IDAT; some ImageMagick versions report
  ;; only the first frame, so inspect the chunk header without decoding pixels.
  (when (= "png" (some-> (fs/extension file) str/lower-case))
    (with-open [input (java.io.DataInputStream. (java.io.FileInputStream. (str file)))]
      (when (= -8552249625308161526 (.readLong input))
        (loop []
          (let [length (.readInt input)
                chunk (String. (.readNBytes input 4) "US-ASCII")]
            (cond
              (= "acTL" chunk) true
              (#{"IDAT" "IEND"} chunk) false
              (neg? length) (throw (ex-info "Invalid PNG chunk length" {}))
              :else (do (.skipNBytes input (+ length 4)) (recur)))))))))

(defn image-metadata! [file]
  (let [command (if (fs/which "magick") ["magick" "identify"] ["identify"])
        output (:out (apply process/shell {:out :string}
                           (concat command ["-ping" "-format" "%w %h %n\n" (str file)])))
        [width height frames] (mapv parse-long (str/split (first (str/split-lines output)) #"\s+"))]
    {:width width
     :height height
     :frames (if (animated-png? file) (max 2 frames) frames)
     :bytes (fs/size file)}))

(defn responsive-originals []
  ;; Prefer a legacy original, then WebP, then AVIF for each stem. Full-size
  ;; modern siblings and generated sidecars never become a second input.
  (let [priority {"jpg" 0 "jpeg" 0 "png" 0 "webp" 1 "avif" 2}
        files (filter #(and (fs/regular-file? %) (responsive-original? %))
                      (fs/glob "resources/public/img" "**" {:hidden true}))]
    (->> files
         (group-by #(str (fs/strip-ext %)))
         vals
         (map #(first (sort-by (juxt (fn [file] (priority (str/lower-case (fs/extension file)))) str) %)))
         (sort-by str))))

(defn responsive-metadata! [catalog]
  (into (sorted-map)
    (for [file (distinct (concat (responsive-originals)
                                (for [[src entry] catalog
                                      :when (and (not (:exclude? entry)) (not (:generated? entry)))]
                                  (fs/path "resources/public" src))))]
      [(str (fs/relativize "resources/public" file)) (image-metadata! file)])))

(defn responsive-target [file size format]
  (fs/path (str (fs/strip-ext file) "-" size "w." format)))

(defn current-variant? [file target]
  (and (fs/regular-file? target)
       (not (pos? (compare (fs/last-modified-time file) (fs/last-modified-time target))))))

(defn responsive-convert! [file size format force?]
  (let [target (if size (responsive-target file size format) (variant file format))]
    (if (or (= (str file) (str target))
            (and (nil? size) (fs/regular-file? target))
            (and (not force?) (current-variant? file target)))
      :skipped
      (let [temporary (fs/create-temp-file {:dir (fs/parent target)
                                            :prefix (str (fs/file-name target) ".")})]
        (try
          (apply process/shell {:extra-env {"MAGICK_THREAD_LIMIT" "1"}}
                 (concat [(imagemagick) "-limit" "thread" "1" (str file)]
                         (when size ["-resize" (str size "x>")])
                         ["-strip" "-quality" (if (= format "avif") "60" "80")
                          (str format ":" temporary)]))
          (fs/set-posix-file-permissions temporary "rw-r--r--")
          (fs/move temporary target {:replace-existing true :atomic-move true})
          :converted
          (finally (fs/delete-if-exists temporary)))))))

(defn responsive-verify! []
  (let [catalog (edn/read-string (slurp "resources/responsive-images.edn"))
        files (for [[src {:keys [width height sizes exclude?]}] catalog
                    :when (not exclude?)
                    :let [source (fs/path "resources/public" src)]
                    size sizes
                    format formats
                    :let [file (responsive-target source size format)
                          metadata (image-metadata! file)]]
                (do
                  (when-not (and (< size width) (= size (:width metadata))
                                 (<= (:height metadata) height) (= 1 (:frames metadata)))
                    (throw (ex-info "Invalid responsive variant dimensions" {:src src :size size :format format})))
                  {:bytes (:bytes metadata)}))]
    (println "Responsive image verification:" (count files) "sized files,"
             (reduce + (map :bytes files)) "bytes; dimensions and still-image metadata valid.")))

(defn write-catalog! [catalog]
  (let [file (fs/path "resources/responsive-images.edn")
        temporary (fs/create-temp-file {:dir (fs/parent file) :prefix "responsive-images."})]
    (try
      (spit (str temporary) (with-out-str (pprint/pprint catalog)))
      (fs/set-posix-file-permissions temporary "rw-r--r--")
      (fs/move temporary file {:replace-existing true :atomic-move true})
      (finally (fs/delete-if-exists temporary)))))

(defn placeholders! []
  ;; Metadata-only refresh leaves every existing rendition byte untouched.
  (let [catalog (edn/read-string (slurp "resources/responsive-images.edn"))]
    (write-catalog! (catalog-placeholders! catalog))
    (println "Image placeholders:" (count (remove (comp :exclude? val) catalog)) "sources.")))

(defn responsive! [& args]
  (let [{:keys [force? files]} (arguments args)
        _ (when (seq files)
            (throw (ex-info "Use bb images:responsive [--force]" {})))
        original-catalog (edn/read-string (slurp "resources/responsive-images.edn"))
        metadata (responsive-metadata! original-catalog)
        catalog (responsive-catalog original-catalog metadata)
        catalog' (catalog-placeholders! catalog)
        counts (frequencies
                 (for [[src {:keys [width height sizes exclude?]}] catalog
                       :when (not exclude?)
                       :let [file (fs/path "resources/public" src)]
                       size (conj sizes nil)
                       format formats]
                   (do
                     (when-not (and (fs/regular-file? file) (pos-int? width) (pos-int? height)
                                    (= 1 (:frames (get metadata src)))
                                    (= width (:width (get metadata src)))
                                    (= height (:height (get metadata src)))
                                    (or (nil? size) (and (pos-int? size) (< size width))))
                       (throw (ex-info "Invalid responsive image source or size" {:src src :size size})))
                     (responsive-convert! file size format (and size force?)))))]
    ;; Publish only after all variants succeed; keep original/full-size fallbacks.
    (write-catalog! catalog')
    (println "Responsive images:" (count (remove (comp :exclude? val) catalog)) "sources,"
             (get counts :converted 0) "variants created,"
             (get counts :skipped 0) "current variants skipped.")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
