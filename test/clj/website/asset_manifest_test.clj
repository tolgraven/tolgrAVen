(ns website.asset-manifest-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [optimus.assets :as assets]
            [optimus.link :as link]
            [website.build.assets :as build]
            [website.middleware.asset-manifest :as manifest]
            [website.middleware.assets :as delivery]
            [website.middleware-transport-test :refer [body-bytes]])
  (:import [java.nio.file Files]
           [java.net URLClassLoader]
           [java.util.jar JarEntry JarOutputStream]))

(def build-settings {:browser-modules {}, :site {}, :styles {}})

(defn temporary-directory []
  (.toFile (Files/createTempDirectory "prepared-assets-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree! [root]
  (doseq [file (reverse (file-seq root))] (io/delete-file file true)))

(defn resolver [root]
  (fn [path]
    (let [file (io/file root path)] (when (.isFile file) (.toURL (.toURI file))))))

(defn bytes-at [inventory path]
  (body-bytes ((delivery/serve-assets (constantly {:status 404})
                                     (constantly inventory) (fn [files _] files) {})
              {:request-method :get, :uri path})))

(deftest prepared-inventory-preserves-style-font-urls-headers-and-binary-bytes
  (let [root (temporary-directory)
        build-id (assoc build-settings
                        :browser-modules {:main {:output-name "main.hash.js"}}
                        :styles {"icons.css" ["/css/tolgraven/icons.min.css"]})
        source-assets (vec (assets/load-bundle "public" "icons.css" ["/css/tolgraven/icons.min.css"]))
        optimized (vec (build/optimize source-assets {}))
        sources (map #(str "public" (:path %)) source-assets)]
    (try
      (doseq [path sources]
        (let [file (io/file root path)]
          (io/make-parents file)
          (with-open [input (io/input-stream (io/resource path))] (io/copy input file))))
      (let [prepared (build/write-prepared! root build-id sources optimized)
            source-resolver (resolver root)
            imported (manifest/read-prepared! prepared build-id source-resolver)
            jar (io/file root "fixture.jar")]
        (is (= (mapv #(dissoc % :resource) optimized) (mapv #(dissoc % :resource) imported)))
        (is (= (link/bundle-paths {:optimus-assets optimized} ["icons.css"])
               (link/bundle-paths {:optimus-assets imported} ["icons.css"])))
        (doseq [{:keys [path]} optimized]
          (is (= (vec (bytes-at optimized path)) (vec (bytes-at imported path))) path))
        (with-open [out (JarOutputStream. (io/output-stream jar))]
          (doseq [file (filter #(and (.isFile %) (not= % jar)) (file-seq root))]
            (.putNextEntry out (JarEntry. (str (.relativize (.toPath root) (.toPath file)))))
            (with-open [input (io/input-stream file)] (io/copy input out))
            (.closeEntry out)))
        (with-open [loader (URLClassLoader. (into-array java.net.URL [(.toURL (.toURI jar))]) nil)]
          (let [jar-resolver #(.getResource loader %)
                packaged (manifest/read-prepared! (jar-resolver manifest/resource-path) build-id jar-resolver)]
            (doseq [{:keys [path]} imported]
              (is (= (vec (bytes-at imported path)) (vec (bytes-at packaged path))) path))))
        (testing "a changed source or build selection fails without a runtime fallback"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different build"
                                (manifest/read-prepared! prepared {:styles {}} source-resolver)))
          (spit (io/file root (first sources)) "stale")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing or stale"
                                (manifest/read-prepared! prepared build-id source-resolver)))))
      (finally (delete-tree! root)))))

(deftest transformed-resource-is-persisted-independently-of-its-temporary-file
  (let [root (temporary-directory)
        original (io/file root "public/img/fixture.jpg")
        transformed (io/file root "transformed.jpg")
        resolve-resource (resolver root)]
    (try
      (io/make-parents original)
      (spit original "original bytes")
      (spit transformed "legacy transformed bytes")
      (let [asset {:path "/img/fixture.jpg"
                   :resource (.toURL (.toURI transformed))
                   :headers {"Cache-Control" "max-age=315360000"}}
            prepared (build/write-prepared! root build-settings ["public/img/fixture.jpg"] [asset] resolve-resource)]
        (io/delete-file transformed)
        (let [imported (manifest/read-prepared! prepared build-settings resolve-resource)]
          (is (= "legacy transformed bytes" (String. ^bytes (bytes-at imported "/img/fixture.jpg") "UTF-8")))
          (is (= (:headers asset) (:headers (first imported))))
          (spit (io/file (:resource (first imported))) "corrupt body")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"body is corrupt"
                                (manifest/read-prepared! prepared build-settings resolve-resource)))))
      (finally (delete-tree! root)))))

(deftest prepared-manifest-fails-clearly-when-missing-invalid-or-incomplete
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"manifest is missing"
                        (manifest/read-prepared! nil build-settings (constantly nil))))
  (let [root (temporary-directory)
        file (io/file root "manifest.edn")]
    (try
      (doseq [[value message] [[{} #"manifest is invalid"]
                              [{:version 1, :build build-settings, :sources []
                                :assets [{:path "/missing", :resource-path "public/missing"}]} #"body is missing"]]]
        (spit file (pr-str value))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                              (manifest/read-prepared! file build-settings (constantly nil)))))
      (finally (delete-tree! root)))))

(deftest build-rejects-css-imports-instead-of-merging-independent-owners
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Build CSS imports"
                        (build/optimize [{:path "/css/fixture.css", :contents "@import 'other.css';"}] {}))))
