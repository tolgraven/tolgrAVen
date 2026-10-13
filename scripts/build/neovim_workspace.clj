(ns build.neovim-workspace
  "Bundle the selected checkout's source and SCSS for the optional browser editor."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cheshire.core :as json]
            [tolgraven.build.packaging :as packaging]))

(def output "resources/public/vendor/neovim/workspace.json")
(defn source-files [root exclusions]
  (letfn [(walk [file]
            (when-not (java.nio.file.Files/isSymbolicLink (.toPath file))
              (if (.isDirectory file)
                (mapcat walk (sort-by str (.listFiles file)))
                (when (.isFile file) [file]))))]
    (for [directory ["src" "resources/scss"]
          file (walk (io/file root directory))
          :let [relative (string/replace (str (.relativize (.toPath (io/file root)) (.toPath file))) "\\" "/")
                public-path (if (string/starts-with? relative "src/")
                              (string/replace relative #"^src/[^/]+/" "")
                              (-> relative
                                  (string/replace #"^resources/scss/" "public/css/tolgraven/")
                                  (string/replace #"\.scss$" ".css")))]
          :when (and (re-find #"\.(?:clj[sc]?|js|lua|scss)$" relative)
                     (not-any? #(re-find % public-path) exclusions))]
      {:path relative, :text (slurp file)})))

(defn sync-profile! []
  (let [file "resources/public/vendor/neovim/profile.json"
        profile (json/parse-string (slurp file) true)
        files (mapv (fn [entry]
                      (if (#{"init.lua" "browser.vim"} (:path entry))
                        (let [text (slurp (str "resources/public/vendor/neovim/" (:path entry)))
                              digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
                          (assoc entry :text text :sha256 (apply str (map #(format "%02x" (bit-and 255 %)) digest))))
                        entry)) (:files profile))]
    (spit file (str (json/generate-string (assoc profile :files files) {:escape-non-ascii true}) "\n"))))

(defn -main [& _]
  (let [exclusions (:exclusions (packaging/plan))]
    (if (some #(re-find % "public/vendor/neovim/workspace.json") exclusions)
      (do (io/delete-file output true) (println "Neovim disabled; source bundle omitted"))
      (let [files (vec (source-files "." exclusions))]
        (sync-profile!)
        (io/make-parents output)
        (spit output (json/generate-string {:version 1, :root "tolgrAVen", :files files}))
        (println "Bundled" (count files) "source/SCSS files for Neovim")))))
