(ns tolgraven.validation-server
  (:require [tolgraven.config :as config]
            [tolgraven.validation :as validation]
            [tolgraven.schema.declarations :as schemas]))

(defn validate-routes! [routes _]
  (when (config/validation-enabled?)
    (doseq [[path data] routes]
      (validation/check! (str "route " path) (schemas/extend-page (:schema data)) data))))
