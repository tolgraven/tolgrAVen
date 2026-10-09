(ns tolgraven.navigation.schema
  "State exchanged by the route commit and browser transition lifecycle."
  (:require [tolgraven.schema.common :as c]))

(def return-state-keys [:scroll-position])

(def completion
  (c/optional-map {:current? fn?
                   :resolve! fn?
                   :transition-id c/nonnegative
                   :fallback? :boolean
                   :outgoing-height [:maybe [:and number? [:>= 0]]]
                   :outgoing-top [:maybe number?]}))

(def commit
  (c/optional-map {:target [:maybe [:or number? :string]]
                   :completion [:maybe completion]}))

(def route
  [:maybe (c/optional-map {:path :string
                           :template :string
                           :data :map
                           :path-params :map
                           :query-params [:maybe c/query-params]
                           :parameters :map
                           :controllers [:maybe [:sequential :map]]})])
