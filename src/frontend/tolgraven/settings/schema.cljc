(ns tolgraven.settings.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:panel-open :boolean}))
