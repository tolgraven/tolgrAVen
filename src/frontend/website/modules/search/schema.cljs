(ns website.modules.search.schema
  "Consumed provider fields and UI state; provider extension fields remain open."
  (:require [website.schema.common :as c]
            [website.modules.search.model :as model]))

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
(def initial
  {:query ""
   :generation 0
   :pending? false
   :response nil
   :error nil
   :selected -1})

(def results
  [:map
   [:query [:string {:max 200}]]
   [:generation c/nonnegative]
   [:pending? :boolean]
   [:response [:maybe model/result]]
   [:error :?string]
   [:selected [:int {:min -1}]]])
