(ns website.modules.cv.schema
  (:require [website.schema.common :as c]))

(def state (c/optional-map {:visited :boolean}))

(def zoom-context
  [:map
   [:column [:int {:min 0}]]
   [:?column-id [:maybe :int]]
   [:category :string]
   [:parents [:vector {:max 7} [:int {:min 0}]]]
   [:parent-keys [:vector {:max 7} :string]]])

(def bounds
  [:map
   [:left number?]
   [:top number?]
   [:width number?]
   [:height number?]])
