(ns media.images
  "Bundled and uploaded image conversion share these codecs and publication rules."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
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

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
