(ns website.build.images
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.components.image.schema :as image]))

(def catalog-schema
  [:map-of :string
   [:or
    [:map [:exclude? [:= true]]]
    [:map [:width pos-int?]
     [:height pos-int?]
     [:?bytes nat-int?]
     [:sizes [:vector pos-int?]]
     [:?progressive? :boolean]
     [:?generated? :boolean]
     [:?placeholder image/placeholder]]]])

(defmacro responsive-images []
  (let [catalog (edn/read-string
                  (if (:ns &env)
                    ;; Register the resource so incremental Shadow builds invalidate
                    ;; the baked catalog. Shadow is compiler-only on the JVM.
                    ((requiring-resolve 'shadow.resource/slurp-resource) &env "responsive-images.edn")
                    (slurp (io/resource "responsive-images.edn"))))]
    (when-not (m/validate (dsl/->malli catalog-schema) catalog)
      (throw (ex-info "Invalid responsive image catalog" {})))
    (into {} (remove (comp :exclude? val)) catalog)))
