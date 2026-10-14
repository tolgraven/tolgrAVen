(ns website.components.validation
  (:require [website.react :as r]
            [website.validation.markup :as markup]
            [website.diagnostics.issues :as details]
            [website.component.registry]
            [website.macros :refer-macros [defc]]))

(defc <issues> [issues]
  [:<> (markup/issues issues) [details/<details> issues]])

(defc <reports> []
  (when-let [reports (seq @(r/subscribe [:validation/errors]))]
    [:section.validation-reports
     [:header [:h3 "Schema validation"]
      [:button {:type "button" :on-click #(r/dispatch [:validation/dismiss])} "Clear"]]
     [:p "Invalid state changes are rejected before their effects run. Values are omitted from reports."]
     (for [[index {:keys [contract event issues count]}] (map-indexed vector (reverse reports))]
       ^{:key index}
       [:article
        [:h4 (str contract) (when event [:code (str event)])
         (when (> count 1) [:span (str " × " count)])]
        [<issues> issues]])]))
