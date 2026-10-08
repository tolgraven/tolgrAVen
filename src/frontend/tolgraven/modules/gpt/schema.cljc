(ns tolgraven.modules.gpt.schema
  "Contracts owned by this feature, shared by browser and JVM validation."
  (:require [tolgraven.schema.common :as c]))

(def form-fields [:map-of c/id :string])
