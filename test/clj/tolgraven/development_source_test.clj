(ns tolgraven.development-source-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [tolgraven.dev.source :as source]
            [tolgraven.env :as environment])
  (:import [java.nio.file Files Path]))

(deftest catalog-excludes-secrets-traversal-symlinks-and-oversized-files
  (let [root (Files/createTempDirectory "source-catalog" (make-array java.nio.file.attribute.FileAttribute 0))
        outside (Files/createTempDirectory "source-outside" (make-array java.nio.file.attribute.FileAttribute 0))
        directory (.resolve root "src/frontend")]
    (Files/createDirectories directory (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (.toFile (.resolve directory "example.cljs")) "(ns example)\n(def answer 42)\n")
    (spit (.toFile (.resolve root "credentials.cljs")) "not source")
    (spit (.toFile (.resolve directory "large.cljs")) (apply str (repeat 524289 "x")))
    (spit (.toFile (.resolve outside "private.cljs")) "private")
    (Files/createSymbolicLink (.resolve directory "linked.cljs") (.resolve outside "private.cljs")
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (try
      (is (= [{:path "src/frontend/example.cljs" :namespace "example" :language "clojure"}]
             (source/catalog root)))
      (is (= "(ns example)\n(def answer 42)\n" (:content (source/document root "src/frontend/example.cljs"))))
      (doseq [file ["credentials.cljs" "src/frontend/linked.cljs" "src/frontend/large.cljs"
                    "src/frontend/../../credentials.cljs" "../private.cljs" "/etc/passwd"]]
        (is (nil? (source/document root file))))
      (finally
        (doseq [^Path file (reverse (sort-by #(count (str %))
                                            (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
                                              (vec (iterator-seq (.iterator paths))))))]
          (Files/deleteIfExists file))
        (Files/deleteIfExists (.resolve outside "private.cljs"))
        (Files/deleteIfExists outside)))))

(deftest source-endpoints-are-development-only
  (with-redefs [environment/defaults {:development? false}]
    (is (nil? (source/routes))))
  (with-redefs [environment/defaults {:development? true}]
    (let [routes (source/routes)
          response ((get-in (second routes) [1 :get :handler])
                    {:parameters {:query {:file "config/local.dev.edn"}}})]
      (is (= ["/dev/source-catalog" "/dev/source"] (mapv first routes)))
      (is (= 404 (:status response))))))
