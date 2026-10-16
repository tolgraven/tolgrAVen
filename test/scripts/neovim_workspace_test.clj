(require '[build.neovim-workspace :as workspace]
         '[website.build.packaging :as packaging]
         '[clojure.java.io :as io]
         '[clojure.test :refer [deftest is run-tests]])
(deftest sources-use-allowlisted-roots-and-packaging-policy
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "neovim-workspace-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        files {"src/frontend/website/modules/neovim/views.cljs" "editor"
               "src/frontend/website/modules/blog/views.cljs" "disabled"
               "resources/scss/modules/neovim.scss" "editor style"
               "resources/scss/modules/blog.scss" "disabled style"
               "config/dev-local.edn" "private"
               "src/frontend/image.png" "binary"}]
    (try
      (doseq [[path text] files]
        (io/make-parents (io/file root path))
        (spit (io/file root path) text))
      (let [bundled (workspace/source-files root [#"^website/modules/blog/" #"^public/css/website/modules/blog\.css$"])]
        (is (= #{"src/frontend/website/modules/neovim/views.cljs" "resources/scss/modules/neovim.scss"}
               (set (map :path bundled))))
        (is (= #{"editor" "editor style"} (set (map :text bundled)))))
      (finally
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))
(deftest production-output-exclusions-preserve-editor-scss
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "scss-editor-bundle-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        paths ["resources/scss/main.scss" "resources/scss/dev.scss"
               "resources/scss/shell/_vars.scss" "resources/scss/modules/neovim.scss"
               "src/frontend/website/components/example.cljs"]]
    (try
      (doseq [path paths]
        (io/make-parents (io/file root path))
        (spit (io/file root path) (str "Source: " path)))
      (let [plan {:exclusions packaging/generated-style-exclusions}
            files (workspace/source-files root (packaging/source-exclusions plan))]
        (is (= (set paths) (set (map :path files))))
        (is (every? #(string? (:text %)) files)))
      (finally
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(let [summary (run-tests)] (when (pos? (+ (:fail summary) (:error summary))) (System/exit 1)))
