(require '[build.neovim-workspace :as workspace]
         '[clojure.java.io :as io]
         '[clojure.test :refer [deftest is run-tests]])
(deftest sources-use-allowlisted-roots-and-packaging-policy
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "neovim-workspace-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        files {"src/frontend/tolgraven/modules/neovim/views.cljs" "editor"
               "src/frontend/tolgraven/modules/blog/views.cljs" "disabled"
               "resources/scss/modules/neovim.scss" "editor style"
               "resources/scss/modules/blog.scss" "disabled style"
               "config/dev-local.edn" "private"
               "src/frontend/image.png" "binary"}]
    (try
      (doseq [[path text] files]
        (io/make-parents (io/file root path))
        (spit (io/file root path) text))
      (let [bundled (workspace/source-files root [#"^tolgraven/modules/blog/" #"^public/css/tolgraven/modules/blog\.css$"])]
        (is (= #{"src/frontend/tolgraven/modules/neovim/views.cljs" "resources/scss/modules/neovim.scss"}
               (set (map :path bundled))))
        (is (= #{"editor" "editor style"} (set (map :text bundled)))))
      (finally
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))
(let [summary (run-tests)] (when (pos? (+ (:fail summary) (:error summary))) (System/exit 1)))
