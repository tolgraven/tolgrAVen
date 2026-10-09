(ns tolgraven.modules.gpt.schema
  (:require [tolgraven.schema.common :as c]))

(def form-fields [:map-of c/id :string])
