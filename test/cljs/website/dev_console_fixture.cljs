(ns website.dev-console-fixture
  (:require [website.component :as component]
            [website.macros :refer-macros [defc]]))
(defc <shared-name> {:state {:initial {:count 0}}} []
  :let [*count (<sub :comp [:count] {:initial 0})]
  [:button {:on-click #(>update *count inc)} (str "other:" @*count)])
