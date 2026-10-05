(ns tolgraven.dev-console-fixture
  (:require [tolgraven.component :as component]
            [tolgraven.macros :refer-macros [defc]]))
(defc <shared-name> {:state {}} []
  :let [*count (<sub :comp [:count] {:initial 0})]
  [:button {:on-click #(>update *count inc)} (str "other:" @*count)])
