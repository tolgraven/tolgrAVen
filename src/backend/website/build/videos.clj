(ns website.build.videos
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [website.schema.dsl :as dsl]))

(def catalog-schema
  [:map-of :string [:map [:width pos-int?] [:media :string]]])

(defmacro responsive-videos []
  (let [catalog (edn/read-string
                  (if (:ns &env)
                    ;; Register the resource so incremental Shadow builds invalidate
                    ;; the baked catalog. Shadow is compiler-only on the JVM.
                    ((requiring-resolve 'shadow.resource/slurp-resource) &env "responsive-videos.edn")
                    (slurp (io/resource "responsive-videos.edn"))))]
    (when-not (m/validate (dsl/->malli catalog-schema) catalog)
      (throw (ex-info "Invalid responsive video catalog" {})))
    catalog))
