(ns website.build.local
  "Local production releases with separate Shadow caches and runtime resources."
  (:require [clojure.edn :as edn]
            [website.build.assets :as assets]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.errors :as errors]))

(defn -main [& _]
  (let [builds (:builds (edn/read-string (slurp "shadow-cljs.edn")))]
    (api/with-runtime
      (doseq [[id output]
              [[:document {:output-dir "target/production/resources/public/js/document"}]
               [:app {:output-dir "target/production/resources/public/js/compiled/out"}]
               [:ssr {:output-to "target/production/ssr/site.js"}]
               [:return-worker {:output-dir "target/production/resources/public/js/return"}]]]
        (println "Releasing local production target:" id)
        (try
          (api/release* (merge (get builds id)
                              {:build-id (keyword (str "prod-local-" (name id)))}
                              output)
                        {})
          (catch Throwable error
            (errors/user-friendly-error error)
            (.printStackTrace error *err*)
            (throw error))))))
  (assets/prepare! "target/production/resources" "target/production/ssr/site.js")
  (shutdown-agents))
