(ns tolgraven.components.error
  (:require
   [cljs.pprint]
   [clojure.string :as string]
   [reagent.core :as r]
   [tolgraven.components.portal :as portal]
   [tolgraven.ui.code :as code]))

(defn <error-full>
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
        [code/code-block
         (->> (string/replace stack #"at |\(http.*\)" "")
              string/split-lines
              (map string/trim)
              (remove string/blank?)
              (string/join "\n"))]])
     [:p "Props/spec:"]
     [code/code-block
      (string/trim-newline (with-out-str (cljs.pprint/pprint spec)))]
     [:span
      [:button {:on-click #(reset! *error nil)}
      "Attempt reload"]]]))

(defn <error>
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
