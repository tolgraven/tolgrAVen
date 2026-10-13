(ns tolgraven.dev-console-fixture
  (:require [tolgraven.component :as component]
            [tolgraven.macros :refer-macros [defc]]))
(defc <shared-name> {:state {:initial {:count 0}}} []
  :let [*count (<sub :comp [:count] {:initial 0})]
  [:button {:on-click #(>update *count inc)} (str "other:" @*count)])
