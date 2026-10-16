(ns website.modules.cv.schema
  (:require [website.schema.common :as c]))

(def state
  (c/optional-map
    {:visited :boolean
     :demo [:map
            [:generation [:int {:min 1}]]
            [:active? :boolean]
            [:?origin number?]]}))

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
