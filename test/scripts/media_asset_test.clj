(ns media-asset-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is run-tests]]
            [media.images :as images])
  (:import [java.nio.file Files]
           [java.io ByteArrayOutputStream]))

(defn inject-orientation! [file]
  (let [bytes (Files/readAllBytes file)
        ;; JPEG APP1 EXIF: little-endian TIFF, one Orientation=6 SHORT tag.
        segment (byte-array (map unchecked-byte
                              [255 225 0 34 69 120 105 102 0 0
                               73 73 42 0 8 0 0 0 1 0 18 1 3 0 1 0 0 0 6 0 0 0 0 0 0 0]))]
    (with-open [output (ByteArrayOutputStream.)]
      (.write output bytes 0 2)
      (.write output segment)
      (.write output bytes 2 (- (alength bytes) 2))
      (Files/write file (.toByteArray output) (make-array java.nio.file.OpenOption 0)))))

(deftest small-assets-normalize-orientation-without-unneeded-sidecars
  (let [directory (fs/create-temp-dir)
        source (fs/path directory "source.jpg")
        output (fs/path directory "manifest.edn")]
    (try
      (process/shell (images/imagemagick) "-limit" "thread" "1" "-size" "160x320" "gradient:red-blue" (str source))
      (inject-orientation! source)
      (images/asset! (str source) (str output))
      (let [manifest (edn/read-string (slurp (str output)))
            variants (concat [(:original manifest)] (:webp manifest) (:avif manifest))]
        (is (= 3 (count (get-in manifest [:placeholder :colors]))))
        (is (every? #(re-matches #"#[0-9a-f]{6}" %) (get-in manifest [:placeholder :colors])))
        (is (= [320 160] [(:width manifest) (:height manifest)]))
        (is (= [320] (mapv :width (:webp manifest))))
        (is (= [320] (mapv :width (:avif manifest))))
        (doseq [variant variants]
          (let [file (fs/path directory (:file variant))
                metadata (images/image-metadata! file)]
            (is (= (:width variant) (:width metadata)))
            (is (pos? (:bytes metadata)))
            ;; Decode pixels as well as checking headers before publishing.
            (process/shell (images/imagemagick) "-limit" "thread" "1" (str file) "null:"))))
      (finally (fs/delete-tree directory)))))

(deftest large-assets-retain-responsive-renditions
  (let [directory (fs/create-temp-dir)
        source (fs/path directory "source.png")
        output (fs/path directory "manifest.edn")]
    (try
      ;; Deterministic high-detail pixels keep this fixture above the byte cutoff.
      (process/shell (images/imagemagick) "-limit" "thread" "1" "-seed" "17"
                     "-size" "960x640" "xc:" "+noise" "Random" (str source))
      (images/asset! (str source) (str output))
      (let [manifest (edn/read-string (slurp (str output)))]
        (is (>= (get-in manifest [:original :bytes]) images/responsive-min-bytes))
        (is (= [80 160 320 640 960] (mapv :width (:webp manifest))))
        (is (= [80 160 320 640 960] (mapv :width (:avif manifest)))))
      (finally (fs/delete-tree directory)))))

(deftest placeholder-bands-are-averaged-and-constrained
  (is (= ["#ff0000" "#008000" "#0000ff"]
         (images/band-colors (concat (repeat 3 [255 0 0]) (repeat 3 [0 128 0]) (repeat 3 [0 0 255])))))
  (is (thrown? Exception (images/band-colors [[0 0 0]]))))

(deftest corrupt-input-never-emits-a-ready-manifest
  (let [directory (fs/create-temp-dir)
        source (fs/path directory "source.png")
        output (fs/path directory "manifest.edn")]
    (try
      (spit (str source) "corrupt")
      (is (thrown? Exception (images/asset! (str source) (str output))))
      (is (not (fs/exists? output)))
      (finally (fs/delete-tree directory)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'media-asset-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
