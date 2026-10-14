(ns website.modules.data-inspector.views
  (:require [website.component.registry]
            [website.react :as r]
            [website.macros :refer-macros [defc]]
            [cljs.pprint :as pprint]
            [clojure.string :as string]
            [website.components.portal :as portal]
            [website.modules.highlight.code-block :as code]))

(defc <formatted-data>
  "Pretty-print a value or subscription as Clojure using the deferred formatter."
  [title path-or-data]
  (let [data (if (vector? path-or-data)
               @(r/subscribe path-or-data)
               path-or-data)]
    [:div {:style {:text-align :left}}
     [:h5 title]
     [code/<code-block> (pprint/write data :stream nil) {:language "clojure"}]]))

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
      (string/trim-newline (with-out-str (pprint/pprint spec)))
      {:language "clojure"}]
     [:span
      [:button {:on-click #(reset! *error nil)}
      "Attempt reload"]]]))

(defc <error>
  "Show the inspector's full error details in a toggled portal."
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

