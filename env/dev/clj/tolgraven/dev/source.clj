(ns tolgraven.dev.source
  "Development-only source catalog. Requests select catalog entries, never file paths."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [tolgraven.env :as environment]
            [tolgraven.diagnostics.source-contract :as contract])
  (:import [java.nio.file Files LinkOption Path]))

(def roots ["src/frontend" "src/backend" "src/cljc" "env/dev/cljs" "env/dev/clj"])
(def extensions {"clj" "clojure" "cljc" "clojure" "cljs" "clojure"})
(def no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn catalog
  ([] (catalog (.toPath (io/file "."))))
  ([^Path root]
   (let [root (.toRealPath root (make-array LinkOption 0))]
     (->> roots
          (mapcat (fn [directory]
                    (let [path (.resolve root directory)]
                      (when (Files/isDirectory path no-follow)
                        (with-open [paths (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
                          (->> (iterator-seq (.iterator paths))
                               (filter #(Files/isRegularFile ^Path % no-follow))
                               (keep (fn [^Path file]
                                       (let [relative (str (.relativize root file))
                                             language (extensions (last (string/split relative #"\.")))]
                                         (when (and language
                                                    (.startsWith (.toRealPath file (make-array LinkOption 0)) root)
                                                    (<= (Files/size file) 524288))
                                           {:path relative
                                            :namespace (second (re-find #"\(ns\s+([^\s\(\)]+)" (slurp (.toFile file))))
                                            :language language}))))
                               doall))))))
          (sort-by :path) vec))))

(defn document
  ([file] (document (.toPath (io/file ".")) file))
  ([^Path root file]
   (when-let [entry (some #(when (= file (:path %)) %) (catalog root))]
     ;; Recheck canonical ownership and bounds immediately before reading. A
     ;; symlink or traversal input never becomes an arbitrary filesystem read.
     (let [root (.toRealPath root (make-array LinkOption 0))
           path (.resolve root file)]
       (when (and (Files/isRegularFile path no-follow)
                  (.startsWith (.toRealPath path (make-array LinkOption 0)) root)
                  (<= (Files/size path) 524288))
         (assoc entry :content (slurp (.toFile path))))))))

(defn routes []
  (when (:development? environment/defaults)
    [["/dev/source-catalog"
      {:get {:responses {200 {:body contract/catalog}}
             :handler (fn [_] {:status 200
                               :headers {"Cache-Control" "no-store"}
                               :body (catalog)})}}]
     ["/dev/source"
      {:get {:parameters {:query contract/file-query}
             :responses {200 {:body contract/document}}
             :handler (fn [request]
                        (if-let [value (document (get-in request [:parameters :query :file]))]
                          {:status 200
                           :headers {"Cache-Control" "no-store"}
                           :body value}
                          {:status 404 :body {:message "Source file is not in the development catalog"}}))}}]]))
