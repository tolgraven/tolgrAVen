(ns tolgraven.modules.data-inspector.views
  (:require [tolgraven.component.registry]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc]]
            [cljs.pprint :as pprint]
            [clojure.string :as string]
            [tolgraven.components.markdown :as code]))

(defc <formatted-data> [title path-or-data]
 (let [data (if (vector? path-or-data)
             @(rf/subscribe path-or-data)
             path-or-data)]
  [:div {:style {:text-align :left}}
  [:h5 title]
  [:pre (pprint/write data :stream nil)]]))

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
      (string/trim-newline (with-out-str (pprint/pprint spec)))]
     [:span
      [:button {:on-click #(reset! *error nil)}
      "Attempt reload"]]]))

