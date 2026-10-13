(ns website.modules.gpt.schema
  (:require [website.schema.common :as c]))

(def form-fields [:map-of c/id :string])
