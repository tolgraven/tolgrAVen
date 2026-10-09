(ns tolgraven.build.images
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]))

(def catalog-schema
  [:map-of :string
   [:map [:width pos-int?] [:height pos-int?] [:sizes [:vector pos-int?]]]])

(defmacro responsive-images []
  (let [catalog (edn/read-string (slurp (io/resource "responsive-images.edn")))]
    (when-not (m/validate catalog-schema catalog)
      (throw (ex-info "Invalid responsive image catalog" {})))
    catalog))
