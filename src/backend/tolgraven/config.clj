(ns tolgraven.config
  (:require
    [cprop.core :refer [load-config]]
    [tolgraven.validation :as validation]
    [tolgraven.env :as environment]
    [cprop.source :as source]
    [mount.core :refer [args defstate]]))

(defstate env
  :start
  (load-config
    :merge
    [(args)
     (source/from-system-props)
     (source/from-env)]))

(defn validation-enabled? []
  (validation/enabled?
    (cond-> (if (map? env) env {})
      (System/getenv "VALIDATION_ENABLED")
      (assoc :validation-enabled (System/getenv "VALIDATION_ENABLED")))
    (:development? environment/defaults)))
