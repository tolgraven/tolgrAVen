(ns website.validation-server
  (:require [website.config :as config]
            [website.validation :as validation]
            [website.schema.declarations :as schemas]))

(defn validate-routes! [routes _]
  (when (config/validation-enabled?)
    (doseq [[path data] routes]
      (validation/check! (str "route " path) (schemas/extend-page (:schema data)) data))))
