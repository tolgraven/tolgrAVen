(ns website.modules.search.schema
  "Consumed provider fields and UI state; provider extension fields remain open."
  (:require [website.schema.common :as c]))

(def state (c/optional-map {:open? :boolean, :results-open? :boolean}))

(def search-result
  (c/optional-map {:found c/nonnegative
                   :out_of c/nonnegative
                   :page c/positive
                   :search_time_ms c/milliseconds
                   :hits [:sequential
                          [:map
                           [:document :map]
                           [:?highlights [:sequential :map]]]]}))
(def results
  (c/optional-map {:query [:map-of c/named :?string]
                   :previous-query [:map-of c/named :?string]
                   :results [:map-of c/named [:map-of :string search-result]]}))
