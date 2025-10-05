(ns tolgraven.components.portal 
  (:require
   [tolgraven.react :as r]))

(defn <portal>
  "Util component for building portals."
  [target-id & forms]
  (if-let [e (js/document.getElementById target-id)]
    (r/create-portal
     (r/as-element
      (if (= 1 (count forms)) (first forms) (vec (apply concat [:<>] forms))))
     e)
    (js/console.warn (str "Couldn't resolve portal target " target-id))))

