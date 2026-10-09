(ns tolgraven.media.image
  "Bounded conversion of already validated/re-encoded PNG uploads."
  (:require [clojure.java.io :as io]
            [tolgraven.config :as config])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent Semaphore TimeUnit]))

(def variants-schema
  [:map {:closed true}
   [:png bytes?]
   [:webp bytes?]
   [:avif bytes?]])

(defonce ^:private conversion-slots (Semaphore. 2))
(def ^:private timeout-ms 45000)

(defn- unavailable! []
  (throw (ex-info "Unable to convert the image; try again later" {:auth/status 503})))

(defn- run-converter! [file]
  (let [script (or (:image-converter config/env) "scripts/media/images.clj")
        builder (ProcessBuilder. ^java.util.List ["bb" script "--force" "--" (str file)])]
    ;; Only our normalized PNG reaches ImageMagick, never the supplied filename
    ;; or original format. Bound CPU/memory and suppress private process output.
    (doseq [[key value] {"MAGICK_THREAD_LIMIT" "2"
                        "MAGICK_MEMORY_LIMIT" "128MiB"
                        "MAGICK_MAP_LIMIT" "256MiB"
                        "MAGICK_DISK_LIMIT" "256MiB"}]
      (.put (.environment builder) key value))
    (.redirectOutput builder ProcessBuilder$Redirect/DISCARD)
    (.redirectError builder ProcessBuilder$Redirect/DISCARD)
    (let [process (.start builder)]
      (try
        (when-not (and (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
                       (zero? (.exitValue process)))
          (unavailable!))
        (finally
          (when (.isAlive process)
            (with-open [children (.descendants (.toHandle process))]
              (.forEach children (reify java.util.function.Consumer
                                   (accept [_ child] (.destroyForcibly ^ProcessHandle child)))))
            (.destroyForcibly process)
            (.waitFor process 2 TimeUnit/SECONDS)))))))

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
