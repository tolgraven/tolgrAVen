(ns placeholder-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is run-tests]]
            [media.images :as images]))

(deftest bands-preserve-spatial-color-and-white-transparency
  (is (= ["#ff0000" "#008000" "#0000ff"]
         (images/band-colors (concat (repeat 3 [255 0 0])
                                    (repeat 3 [0 128 0])
                                    (repeat 3 [0 0 255])))))
  (is (thrown? Exception (images/band-colors [[0 0 0]])))
  (let [directory (fs/create-temp-dir)
        file (fs/path directory "bands.png")
        transparent (fs/path directory "transparent.png")]
    (try
      (process/shell (images/imagemagick) "-size" "30x10" "xc:red" "xc:green" "xc:blue" "-append" (str file))
      (let [colors (:colors (images/placeholder! file))]
        (is (= 3 (count colors)))
        (is (every? #(re-matches #"#[0-9a-f]{6}" %) colors))
        (is (not= (first colors) (last colors))))
      (process/shell (images/imagemagick) "-size" "30x30" "xc:none" (str transparent))
      (is (= {:colors ["#ffffff" "#ffffff" "#ffffff"]} (images/placeholder! transparent)))
      (finally (fs/delete-tree directory)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'placeholder-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
