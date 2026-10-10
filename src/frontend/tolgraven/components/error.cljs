(ns tolgraven.components.error
  (:require
    [tolgraven.component.registry]
    [tolgraven.components.validation :as validation]
    [tolgraven.diagnostics.stack :as diagnostics]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.loader.view :as loader-view]
    [reagent.core :as r]
    [tolgraven.components.portal :as portal]))

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

(defn <error-full> [ns-name comp-name *error spec]
  (loader-view/form :data-inspector :error [ns-name comp-name *error spec]
                    [:section.error [:h2 "Component error"] [:p "Loading details…"]]))

(defc <error>
  "Outer error display component."
  [_ _ _ _]
  (let [*open? (r/atom false)]
    (fn <error-inner>
      [ns-name comp-name *error spec]
      [:<>
       [:button.error-show {:on-click #(swap! *open? not)}
        (if @*open? "-" "+") " Error"]
       (when @*open?
        [portal/<portal> "error-portal"
         [<error-full> ns-name comp-name *error spec]])])))
