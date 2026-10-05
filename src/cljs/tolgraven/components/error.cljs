(ns tolgraven.components.error
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [cljs.pprint]
    [clojure.string :as string]
    [reagent.core :as r]
    [tolgraven.components.portal :as portal]
    [tolgraven.ui.code :as code]))

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
      [:pre (or (ex-message error) (str error))]
      (when (seq stack) [:pre stack])])])

(defc <error-full>
  "Full error display compojnent, goes in a portal."
  [ns-name comp-name *error spec]
  (let [{:keys [error stack]} @*error]
    [:section.error
     [:div
      [:h2 "Component error"]
      [:span "Boundary " (str ns-name "/" comp-name)]]
     [:p "Exception: "]
     [:code {:style {:color "var(--red)"}}
       (or (some->> error ex-message (str "Message: "))
           (str error)
           "Unknown")]
     (when stack
       [:<>
        [:p "Stack trace:"]
        [code/<code-block>
         (->> (string/replace stack #"at |\(http.*\)" "")
              string/split-lines
              (map string/trim)
              (remove string/blank?)
              (string/join "\n"))]])
     [:p "Props/spec:"]
     [code/<code-block>
      (string/trim-newline (with-out-str (cljs.pprint/pprint spec)))]
     [:span
      [:button {:on-click #(reset! *error nil)}
      "Attempt reload"]]]))

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
