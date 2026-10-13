(ns website.components.error
  (:require
    [website.component.registry]
    [website.components.validation :as validation]
    [website.diagnostics.stack :as diagnostics]
    [website.macros :refer-macros [defc register-render-functions!]]))

(defc <failure>
  "Shared fallback for rendering, dependencies, modules and initialization.
   The owner supplies retry; this component never starts requests itself."
  [ns-name comp-name {:keys [error stack title message]} retry!]
  [:section.component-failed {:role "alert"}
   [:h2 (or title "This component could not be displayed")]
   [:p (or message "The rest of the page is still available. You can try loading this component again.")]
   (when retry! [:button {:type "button" :on-click (fn [_] (retry!))} "Attempt reload"])
   (when error
     [:details
      [:summary "Error details"]
      [:p (str ns-name "/" comp-name)]
      (if-let [issues (:issues (ex-data error))]
        [validation/<issues> issues]
        [:pre (or (ex-message error) (str error))])
      (when (seq stack) [diagnostics/<stack> stack])])])

(register-render-functions!)
