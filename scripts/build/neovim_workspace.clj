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

(def editable-profile-files ["init.lua" "browser.vim" "after/syntax/nerdtree.vim"])

(defn sync-profile! []
  (let [root "resources/public/vendor/neovim/"
        file (str root "profile.json")
        profile (json/parse-string (slurp file) true)
        edited (mapv (fn [path]
                       (let [text (slurp (str root path))
                             digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
                         {:path path
                          :text text
                          :sha256 (apply str (map #(format "%02x" (bit-and 255 %)) digest))}))
                     editable-profile-files)
        files (into edited (remove (comp (set editable-profile-files) :path) (:files profile)))]
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
