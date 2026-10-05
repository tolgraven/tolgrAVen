(ns tolgraven.ui.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def carousel-state [:map-of c/id (c/optional-map {:index :int, :direction [:or :keyword :string]})])

(def transition-options (c/optional-map {:time c/milliseconds, :style :keyword}))
