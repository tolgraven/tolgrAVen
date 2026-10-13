(ns website.build.assets
  "Prepare the existing Optimus URL/stylesheet contract once during packaging."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [optimus.strategies :as strategies]
            [optimus.optimizations.concatenate-bundles :refer [concatenate-bundles]]
            [optimus.optimizations.add-cache-busted-expires-headers :refer [add-cache-busted-expires-headers]]
            [optimus.optimizations.add-last-modified-headers :refer [add-last-modified-headers]]
            [optimus-img-transform.core :refer [transform-images]]
            [website.middleware.asset-manifest :as manifest]
            [website.validation :as validation])
  (:import [java.nio.file Files StandardCopyOption]))

(defn optimize
  "Shadow owns all registered JS. Preserve the legacy fallback image bytes,
   bundle URLs and CSS/font rewriting; their work now belongs to the build."
  [assets options]
  (doseq [{:keys [path contents]} assets
          :when (and (string/ends-with? path ".css")
                     (re-find #"(?i)@import\b" (or contents "")))]
    (throw (ex-info "Build CSS imports before preparing independent stylesheet bundles"
                    {:asset path})))
  (-> assets
      ;; All inputs are already Sass/PostCSS or Shadow outputs. Do not initialize
      ;; GraalJS/Uglify or silently inline another stylesheet owner's imports.
      ; (optimizations/minify-css-assets options) ;my css breaks it ofc ; not anymore apparently ; well it's already minified by autoprefixer or? could anyways
      (concatenate-bundles options)
      (transform-images {:regexp #"(/media/.*\.jpg)|(/img/.*\.(jpg|png))" ; Only transform originals, not webp/avif
                         :quality 0.80
                         :progressive true}) ; webp/avif already optimized at creation time
      (add-cache-busted-expires-headers)
      (add-last-modified-headers)))

(defn- replace-file! [temporary file]
  (Files/move (.toPath temporary) (.toPath file)
              (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING
                                                   StandardCopyOption/ATOMIC_MOVE])))

(defn- persist-resource! [root resolve-resource {:keys [resource original-path path] :as asset}]
  (if resource
    (let [public-path (str "public" (or original-path path))
          public-resource (resolve-resource public-path)
          ;; Unchanged public binaries already belong to the artifact. Only the
          ;; legacy transformed JPEG/PNG resources need additional stored bytes.
          transformed? (not= (str resource) (some-> public-resource str))
          sha256 (when transformed? (manifest/digest resource))
          resource-path (if transformed? (str "optimus/" sha256 ".bin") public-path)
          file (io/file root resource-path)
          temporary (io/file (str file ".tmp"))]
      (when (and transformed? (not (and (.isFile file) (= sha256 (manifest/digest file)))))
        (io/make-parents file)
        (with-open [input (io/input-stream resource)] (io/copy input temporary))
        (replace-file! temporary file))
      (-> asset (dissoc :resource) (assoc :resource-path resource-path)))
    asset))

(defn write-prepared!
  "Persist a realized inventory and the raw source-byte digests it came from.
   root is the resource output directory, normally resources. Publish manifest
   last so a failed conversion cannot advertise an incomplete set of bodies."
  ([root build sources assets]
   (write-prepared! root build sources assets io/resource))
  ([root build sources assets resolve-resource]
   (let [sources' (mapv (fn [path]
                          (let [resource (resolve-resource path)]
                            (when-not resource
                              (throw (ex-info "Asset source is missing" {:asset path})))
                            {:path path, :sha256 (manifest/digest resource)}))
                        (sort (distinct sources)))
         assets' (mapv #(persist-resource! root resolve-resource %) assets)
         prepared {:version 1, :build build, :sources sources', :assets assets'}
         file (io/file root manifest/resource-path)
         temporary (io/file (str file ".tmp"))]
     (when (validation/explain manifest/manifest-schema prepared)
       (throw (ex-info "Cannot publish an invalid prepared asset manifest" {})))
     (io/make-parents file)
     (spit temporary (pr-str prepared))
     (replace-file! temporary file)
     file)))

(defn prepare!
  "Prepare the current classpath's released assets into an explicit resource root."
  [root]
  (let [get-assets (requiring-resolve 'website.middleware/get-assets)
        source-maps (requiring-resolve 'website.middleware/module-source-maps)
        browser-modules ((requiring-resolve 'website.layout/browser-modules))
        _ (when-not (re-matches #"main\.[a-fA-F0-9]{32}\.js"
                                (get-in browser-modules [:main :output-name] ""))
            (throw (ex-info "Prepare assets after the production Shadow :app release" {})))
        build {:browser-modules browser-modules
               :site @(requiring-resolve 'website.site/config)
               :styles @(requiring-resolve 'website.loader.style-catalog/stylesheet-bundles)}
        loaded (vec (strategies/guard-against-duplicate-assets (get-assets)))
        assets (vec (optimize (source-maps loaded) {}))
        file (write-prepared! root build (map #(str "public" (:path %)) loaded) assets)]
    (println "Prepared" (count assets) "assets in" (str file))
    file))

(defn -main [& args]
  (when-not (every? #{"--build-styles"} args)
    (throw (ex-info "Expected only optional --build-styles" {})))
  (when (some #{"--build-styles"} args)
    (let [process (.start (.inheritIO (ProcessBuilder. ["npm" "run" "build"])))]
      (when-not (zero? (.waitFor process))
        (throw (ex-info "Stylesheet build failed; no asset manifest published" {})))))
  (prepare! "resources")
  (shutdown-agents))
