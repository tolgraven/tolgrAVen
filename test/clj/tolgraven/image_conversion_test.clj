(ns tolgraven.image-conversion-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [tolgraven.config :as config]
            [tolgraven.components.image.sources :as sources]
            [tolgraven.media.image :as image])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream File]
           [javax.imageio ImageIO]))

(defn- png-bytes []
  (with-open [output (ByteArrayOutputStream.)]
    (ImageIO/write (BufferedImage. 24 16 BufferedImage/TYPE_INT_ARGB) "png" output)
    (.toByteArray output)))

(defn- dimensions! [file]
  (try
    (shell/sh "magick" "identify" "-format" "%w %h" (str file))
    (catch java.io.IOException _
      (shell/sh "identify" "-format" "%w %h" (str file)))))

(deftest real-upload-codecs-produce-decodable-images
  (let [png (png-bytes)
        variants (image/variants! png)]
    (is (m/validate image/variants-schema variants))
    (is (= (vec png) (vec (:png variants))) "Keep the normalized PNG fallback")
    (doseq [[format bytes] variants]
      (let [file (File/createTempFile "tolgraven-image-decode-" (str "." (name format)))]
        (try
          (with-open [output (io/output-stream file)] (.write output ^bytes bytes))
          (is (= {:exit 0 :out "24 16" :err ""}
                 (dimensions! file)))
          (finally (.delete file)))))))

(deftest encoding-failure-removes-temporary-files-and-releases-slot
  (let [*directory (atom nil)]
    (with-redefs-fn {#'image/run-converter! (fn [file]
                                           (reset! *directory (.getParentFile file))
                                           (throw (Exception. "private encoder details")))}
      #(let [error (try (image/variants! (png-bytes)) (catch Exception error error))]
         (is (= 503 (:auth/status (ex-data error))))
         (is (not (.contains (.getMessage error) "private")))
         (is (not (.exists ^File @*directory)))))
    (is (m/validate image/variants-schema (image/variants! (png-bytes))))))

(deftest missing-and-stalled-encoders-fail-safely
  (with-redefs [config/env {:image-converter "/nonexistent/converter.sh"}]
    (is (= 503 (:auth/status (ex-data (try (image/variants! (png-bytes))
                                         (catch Exception error error)))))))
  (let [script (File/createTempFile "tolgraven-slow-encoder-" ".sh")]
    (try
      (spit script "#!/bin/bash\nsleep 10\n")
      (with-redefs [config/env {:image-converter (str script)}]
        (with-redefs-fn {#'image/timeout-ms 100}
          #(let [start (System/nanoTime)
                 error (try (image/variants! (png-bytes)) (catch Exception error error))]
             (is (= 503 (:auth/status (ex-data error))))
             (is (< (/ (- (System/nanoTime) start) 1e6) 3000)))))
      (finally (.delete script)))))

(deftest upload-conversion-rejects-excess-concurrent-jobs
  (let [slots @#'image/conversion-slots]
    (.acquire slots 2)
    (try
      (is (= 503 (:auth/status (ex-data (try (image/variants! (png-bytes))
                                           (catch Exception error error))))))
      (finally (.release slots 2)))))

(deftest converted-avatar-source-contract
  (let [base (str "https://storage.example/storage/v1/object/public/avatars/owner/" (apply str (repeat 64 "a")))
        original (str base ".png")]
    (is (= {:original original :webp (str base ".webp") :avif (str base ".avif")}
           (sources/get-src-variants original)))
    (doseq [legacy ["https://storage.example/storage/v1/object/public/avatars/owner.png?v=1"
                    "https://external.example/avatar.png" "/img/avatar.png" "/img/favicon.png"]]
      (is (= {:original legacy} (sources/get-src-variants legacy))))
    (is (= {:original "/img/Photo.PNG" :webp "/img/Photo.webp" :avif "/img/Photo.avif"}
           (sources/get-src-variants "/img/Photo.PNG")))))
