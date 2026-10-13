(ns tolgraven.media.image
  "Bounded conversion of already validated/re-encoded PNG uploads."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [tolgraven.config :as config]
            [tolgraven.validation :as validation]
            [tolgraven.media.schema :as schema])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent Semaphore TimeUnit]))

(def variants-schema
  [:map {:closed true}
   [:png bytes?]
   [:webp bytes?]
   [:avif bytes?]])

(def local-rendition
  [:map {:closed true}
   [:file [:and :string [:re #"^original(?:-[0-9]+w)?\.(?:png|jpg|webp|avif)$"]]]
   [:width [:int {:min 1 :max 4096}]]
   [:height [:int {:min 1 :max 4096}]]
   [:bytes pos-int?]
   [:mime [:enum "image/png" "image/jpeg" "image/webp" "image/avif"]]])

(def rendition-manifest
  [:map {:closed true}
   [:placeholder schema/placeholder]
   [:width [:int {:min 1 :max 4096}]]
   [:height [:int {:min 1 :max 4096}]]
   [:original local-rendition]
   [:webp [:vector {:min 1} local-rendition]]
   [:avif [:vector {:min 1} local-rendition]]])

(defonce ^:private conversion-slots (Semaphore. 2))
(def ^:private timeout-ms 45000)

(defn- unavailable! []
  (throw (ex-info "Unable to convert the image; try again later" {:auth/status 503})))

(defn- run-converter!
  ([file] (run-converter! file nil))
  ([file manifest]
   (let [script (or (:image-converter config/env) "scripts/media/images.clj")
         arguments (if manifest ["--asset" (str file) (str manifest)] ["--force" "--" (str file)])
         builder (ProcessBuilder. ^java.util.List (into ["nice" "-n" "10" "bb" script] arguments))]
     ;; Only our owned file path reaches the codec, never a supplied filename.
     (doseq [[key value] {"MAGICK_THREAD_LIMIT" "1"
                         "MAGICK_MEMORY_LIMIT" "128MiB"
                         "MAGICK_MAP_LIMIT" "256MiB"
                         "MAGICK_DISK_LIMIT" "256MiB"
                         "MAGICK_TEMPORARY_PATH" (str (.getParentFile ^java.io.File file))}]
       (.put (.environment builder) key value))
     (.redirectOutput builder ProcessBuilder$Redirect/DISCARD)
     (.redirectError builder ProcessBuilder$Redirect/DISCARD)
     (let [process (.start builder)]
       (try
         (when-not (and (.waitFor process (if manifest 180000 timeout-ms) TimeUnit/MILLISECONDS)
                        (zero? (.exitValue process)))
           (unavailable!))
         (finally
           (when (.isAlive process)
             (with-open [children (.descendants (.toHandle process))]
               (.forEach children (reify java.util.function.Consumer
                                    (accept [_ child] (.destroyForcibly ^ProcessHandle child)))))
             (.destroyForcibly process)
             (.waitFor process 2 TimeUnit/SECONDS))))))))

(defn with-renditions!
  "Encode one private raster and publish through a bounded temporary-file owner.
   The callback receives the directory and complete EDN manifest, never partial
   outputs. Source and codecs remain durable only in the caller's Storage adapter."
  [source-bytes extension publish!]
  (when-not (and (#{"jpg" "png" "webp" "avif"} extension)
                 (.tryAcquire conversion-slots))
    (throw (ex-info "Image conversion is busy or unsupported" {:auth/status 503})))
  (try
    (let [directory (.toFile (Files/createTempDirectory "tolgraven-media-"
                                                       (make-array FileAttribute 0)))
          source (io/file directory (str "source." extension))
          manifest (io/file directory "manifest.edn")]
      (try
        (with-open [output (io/output-stream source)] (.write output ^bytes source-bytes))
        (run-converter! source manifest)
        (let [value (edn/read-string (slurp manifest))]
          (when (validation/explain rendition-manifest value) (unavailable!))
          (publish! directory value))
        (finally
          (doseq [file (reverse (file-seq directory))]
            (Files/deleteIfExists (.toPath file))))))
    (finally (.release conversion-slots))))

(defn variants!
  "Encode PNG bytes into all published formats. Temporary files never persist;
   Supabase Storage owns durable originals and variants. Fail before publishing
   anything when a codec fails or both conversion slots are occupied."
  [png-bytes]
  (when-not (.tryAcquire conversion-slots)
    (throw (ex-info "Image conversion is busy; try again shortly" {:auth/status 503})))
  (try
    (let [directory (.toFile (Files/createTempDirectory "tolgraven-upload-"
                                                       (make-array FileAttribute 0)))
          original (io/file directory "image.png")]
      (try
        (with-open [output (io/output-stream original)]
          (.write output ^bytes png-bytes))
        (run-converter! original)
        {:png png-bytes
         :webp (Files/readAllBytes (.toPath (io/file directory "image.webp")))
         :avif (Files/readAllBytes (.toPath (io/file directory "image.avif")))}
        (finally
          (doseq [file (reverse (file-seq directory))]
            (Files/deleteIfExists (.toPath file))))))
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception _ (unavailable!))
    (finally (.release conversion-slots))))
