(ns tolgraven.modules.docs.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:current-page [:maybe :string], :previous-page [:maybe :string]}))
