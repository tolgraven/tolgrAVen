(ns tolgraven.chat.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:visible :boolean}))
(def form-field [:maybe :string])
