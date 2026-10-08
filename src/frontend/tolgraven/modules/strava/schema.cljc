(ns tolgraven.modules.strava.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:activity-expanded [:maybe :int]}))
