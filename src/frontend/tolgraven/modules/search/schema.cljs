(ns tolgraven.modules.search.schema
  "Consumed provider fields and UI state; provider extension fields remain open."
  (:require [tolgraven.schema.common :as c]))

(def state (c/optional-map {:open? :boolean, :results-open? :boolean}))

(def search-result
  (c/optional-map {:found c/nonnegative
                   :out_of c/nonnegative
                   :page c/positive
                   :search_time_ms c/milliseconds
                   :hits [:sequential
                          [:map
                           [:document :map]
                           [:highlights {:optional true} [:sequential :map]]]]}))
(def results
  (c/optional-map {:query [:map-of c/named [:maybe :string]]
                   :previous-query [:map-of c/named [:maybe :string]]
                   :results [:map-of c/named [:map-of :string search-result]]}))
