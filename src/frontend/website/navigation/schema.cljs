(ns website.navigation.schema
  "State exchanged by the route commit and browser transition lifecycle."
  (:require [website.schema.common :as c]))

(def return-state-keys [:scroll-position])

(def component-focus [:map [:path :string] [:scroll? :boolean]])

(def completion
  (c/optional-map {:current? fn?
                   :resolve! fn?
                   :transition-id c/nonnegative
                   :navigation-id c/nonnegative
                   :fallback? :boolean
                   :outgoing-height [:?and number? [:>= 0]]
                   :outgoing-top [:maybe number?]}))

(def commit
  (c/optional-map {:target [:?or number? :string]
                   :completion [:maybe completion]}))

(def route
  [:maybe (c/optional-map {:path :string
                           :template :string
                           :data :map
                           :path-params :map
                           :query-params [:maybe c/query-params]
                           :parameters :map
                           :controllers [:?sequential :map]})])
