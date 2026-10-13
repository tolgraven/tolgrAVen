(ns website.build.docs
  "Stage selected sources before Codox enumerates and loads namespaces."
  (:require [clojure.java.io :as io]
            [website.build.packaging :as packaging]))

(def root "target/site-doc-sources")

(defn -main [& _]
  (let [directory (io/file root)
        exclusions (:exclusions (packaging/plan))]
    (when (.exists directory)
      (doseq [file (reverse (file-seq directory))] (io/delete-file file)))
    (doseq [source ["src/backend" "src/cljc" "src/frontend"]
            file (file-seq (io/file source))
            :when (and (.isFile file) (re-find #"\.clj[sc]?$" (.getName file)))
            :let [relative (str (.relativize (.toPath (io/file source)) (.toPath file)))]
            :when (not-any? #(re-find % relative) exclusions)]
      (let [destination (io/file directory relative)]
        (io/make-parents destination)
        (io/copy file destination)))))
