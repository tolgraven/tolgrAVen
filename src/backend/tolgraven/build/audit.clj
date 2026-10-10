(ns tolgraven.build.audit
  "Isolated production bundle measurements; never overwrite watched app output."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.build-report :as report]))

(defn -main [& [label]]
  (let [label (or label "current")]
    (when-not (re-matches #"[a-zA-Z0-9_-]+" label)
      (throw (ex-info "Audit label must contain only letters, digits, hyphens or underscores" {})))
    (let [directory (str "target/bundle-audit/" label)
          config (-> (get-in (edn/read-string (slurp "shadow-cljs.edn")) [:builds :app])
                     (assoc :build-id (keyword (str "audit-" label))
                            :output-dir directory
                            :asset-path "/js/compiled/out")
                     (assoc-in [:compiler-options :source-map] true))]
      (api/with-runtime
        (let [state (api/release* config {})
              modules (or (:shadow.build.closure/modules state) (:build-modules state))
              graph (into (sorted-map)
                          (for [{:keys [module-id sources]} modules]
                            [module-id (mapv #(select-keys (get-in state [:sources %])
                                                         [:ns :resource-name :output-name]) sources)]))
              names (vec (keep #(when-not (:dead %) (:output-name %)) modules))]
          (spit (str directory "/sources.edn") (pr-str graph))
          (spit (str directory "/bundles.json") (json/write-str names))
          ;; Keep the important boundaries reviewable in every future audit.
          (doseq [source (:main graph)
                  :when (re-find #"cljs_time/|luminus_transit/time|modules/carousel/(views|events|subs|module)|modules/contact/(views|module)|dev_console/|reitit/dev/pretty|expound/|cljs/pprint|^markdown/|modules/blog/cache|modules/home/(views|sections|layout|module)|modules/styled_input/(views|module)|modules/data_inspector/|reitit/coercion/malli|^malli/|validation/malli|schema/malli_coercion|react-dom-server|highlight[_-]|refractor/|react_leaflet|leaflet/"
                                 (:resource-name source ""))]
            (throw (ex-info "An optional dependency entered the main bundle" source)))
          (let [data (report/extract-report-data state)
                main (first (filter #(= :main (:module-id %)) (:build-modules data)))]
            (spit (str directory "/report.json") (json/write-str data))
            (report/generate-html state data (str directory "/report.html") {})
            (println "Largest optimized main sources (bytes):")
            (doseq [[source size] (take 20 (sort-by val > (:source-bytes main)))]
              (println size source)))
          (println "Bundle audit output:" directory))))))
