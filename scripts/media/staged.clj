(ns media.staged
  "Convert Git index blobs. Do not include unstaged originals or overwrite edits."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [media.images :as images])
  (:import [java.nio.file Files]))

(defn git [args & [options]]
  (process/check @(process/process (into ["git"] args)
                                  (merge {:out :string :err :string} options))))
(defn bytes [path] (Files/readAllBytes (fs/path path)))
(defn write-bytes! [path data] (Files/write (fs/path path) data (make-array java.nio.file.OpenOption 0)))
(defn same? [a b] (java.util.Arrays/equals ^bytes a ^bytes b))

(defn install! []
  (let [current (str/trim (:out @(process/process ["git" "config" "--get" "core.hooksPath"] {:out :string}))) ]
    (when-not (#{"" ".githooks"} current)
      (throw (ex-info (str "Existing core.hooksPath (" current "): add .githooks/pre-commit to your hook chain.") {})))
    (git ["config" "--local" "core.hooksPath" ".githooks"])
    (println "Installed image conversion pre-commit hook.")))

(defn -main [& _]
  (let [paths (str/split (:out (git ["diff" "--cached" "--name-only" "--diff-filter=ACMR" "-z"])) #"\u0000")
        originals (filter #(and (str/starts-with? % "resources/public/") (images/original? %)) paths)]
    (when (seq originals)
      (let [scratch (fs/create-temp-dir {:prefix "tolgraven-staged-images-"})]
        (try
          (let [outputs
                (reduce
                  (fn [outputs [number image]]
                    (let [entry (:out (git ["ls-files" "--stage" "-z" "--" image]))
                          directory (fs/create-dirs (fs/path scratch (str number)))
                          source (fs/path directory (fs/file-name image))]
                      (when-not (re-find #"^100(644|755) " entry)
                        (throw (ex-info (str "Image must be a regular file: " image) {})))
                      (git ["show" (str ":" image)] {:out (fs/file source)})
                      (images/convert! source true)
                      (reduce
                        (fn [outputs format]
                          (let [target (images/variant image format)
                                data (bytes (images/variant source format))
                                previous (fs/path directory (str "previous." format))
                                result @(process/process ["git" "show" (str ":" target)]
                                                         {:out (fs/file previous) :err :string})]
                            (when (contains? outputs target)
                              (throw (ex-info (str "Multiple staged originals share a variant: " target) {})))
                            (when (or (fs/sym-link? target)
                                      (and (fs/exists? target) (not (same? (bytes target) data))
                                           (or (not (zero? (:exit result)))
                                               (not (same? (bytes target) (bytes previous))))))
                              (throw (ex-info (str "Preserving unstaged variant edits: stage or move " target " first") {})))
                            (assoc outputs target data))) outputs images/formats)))
                  {} (map-indexed vector originals))
                entries (apply str (for [[target data] outputs]
                                     (str "100644 " (str/trim (:out (git ["hash-object" "-w" "--stdin"] {:in (java.io.ByteArrayInputStream. data)})))
                                          "\t" target "\u0000")))]
            ;; Check every output before modifying the index or working tree.
            (git ["update-index" "-z" "--index-info"] {:in entries})
            (doseq [[target data] outputs] (write-bytes! target data))
            (println "Staged" (count outputs) "image variants from" (count originals) "staged originals."))
          (finally (fs/delete-tree scratch)))))))
