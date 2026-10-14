(ns website.navigation.component-routes
  "Mount component paths as complete native Reitit routes under their page owner.
   Component actions run only after route coercion and module acquisition."
  (:require [website.page :as page]))

(def target
  [:map
   [:module :keyword]
   [:view :keyword]
   [:action fn?]])

(defn- terminal-route
  "A named component remains addressable when it gains named descendants."
  [[path data & children :as route]]
  (let [data? (map? data)
        data' (if data? data {})
        children' (if data? children (rest route))
        children'' (mapv terminal-route children')
        terminal? (and (:name data') (seq children'')
                       (not-any? #(= "" (first %)) children''))]
    (with-meta
      (into [path (if terminal? (dissoc data' :name) data')]
            (if terminal?
              (cons ["" (select-keys data' [:name])] children'')
              children''))
      (meta route))))

(defn mount
  "Keep the page's exact URL and inherited contracts while adding relative routes.
   Root pages use an empty parent so child paths never acquire a double slash."
  [[path data & children] component-routes]
  (if (empty? component-routes)
    (into [path data] children)
    (let [root? (= "/" path)
          parent-path (if root? "" path)
          page-path (if root? "/" "")
          parent-data (assoc (dissoc data :name)
                             :page-route (:name data)
                             :page-selection-keys (vec (distinct (concat (keys (:selection data))
                                                                         (keys (:ssr-parameters data))))))
          page-data (select-keys data [:name])]
      (into [parent-path parent-data [page-path page-data]]
            (concat children (map terminal-route component-routes))))))

(defn action
  "A matched component receives typed parameters, including its complete tail."
  [match]
  (when-let [dispatch (get-in match [:data :component-route :action])]
    (dispatch (:parameters match))))

(defn same-page?
  "Component tails change intent while the typed host-page selection stays put."
  [left right]
  (let [identity (fn [match]
                   (let [data (:data match)]
                     [(or (:page-route data) (:name data))
                      (:module data)
                      (:page data)
                      (select-keys (page/selection match) (:page-selection-keys data))]))]
    (and (some? left) (some? right) (= (identity left) (identity right)))))
