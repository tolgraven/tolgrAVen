(ns test.prepared-assets
  "Compare real build-prepared inventories from source and a packaged server jar."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [optimus.link :as link]
            [tolgraven.middleware.asset-manifest :as manifest]
            [tolgraven.layout :as layout]
            [tolgraven.loader.style-catalog :as styles]
            [tolgraven.site :as site]))

(when-not (and (.exists (io/file "/.dockerenv"))
               (= "/workspace" (.getCanonicalPath (io/file "."))))
  (throw (ex-info "Run this packaging fixture only inside the isolated CPU build" {})))

(let [[mode] *command-line-args*
      expected {:browser-modules (layout/browser-modules)
                :site site/config
                :styles styles/stylesheet-bundles}
      inventory (manifest/read-prepared! expected)
      signatures (mapv (fn [{:keys [path contents resource] :as asset}]
                         {:asset (dissoc asset :resource :contents)
                          :sha256 (manifest/digest (or resource
                                                       (.getBytes ^String contents "UTF-8")))}) inventory)
      bundles (into (sorted-map)
                    (for [bundle (concat ["main.js" "styles.css" "icons.css"
                                          "document-head.js" "document-entrance.js"]
                                         (keys styles/stylesheet-bundles))]
                      [bundle (vec (link/bundle-paths {:optimus-assets inventory} [bundle]))]))
      record {:build expected, :bundles bundles, :assets signatures}
      report "target/prepared-assets-source.edn"]
  (assert (every? (comp seq val) bundles) "Every declared independent bundle must resolve")
  (assert (nil? (find-ns 'optimus.optimizations.minify)) "Prepared import must not load GraalJS/minifiers")
  (assert (nil? (find-ns 'optimus-img-transform.core)) "Prepared import must not load image transformations")
  (case mode
    "source" (do (io/make-parents report) (spit report (pr-str record)))
    "jar" (do
            (assert (= "jar" (.getProtocol (io/resource manifest/resource-path)))
                    "Jar verification must resolve the actual packaged manifest")
            (assert (= record (edn/read-string (slurp report)))
                    "Packaged URLs, headers, styles, fonts and fallback bytes must equal source output"))
    (throw (ex-info "Expected source or jar" {})))
  (println "Verified" mode "prepared inventory:" (count inventory) "assets," (count bundles) "independent bundles"))
(shutdown-agents)
