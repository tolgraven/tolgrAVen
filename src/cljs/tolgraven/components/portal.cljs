(ns tolgraven.components.portal 
  (:require
   [tolgraven.react :as r]))

(defn <portal>
  "Render forms into a DOM node or an element id."
  [target & forms]
  (if-let [e (if (string? target)
               (js/document.getElementById target)
               target)]
    (r/create-portal
     (r/as-element
      (if (= 1 (count forms)) (first forms) (vec (apply concat [:<>] forms))))
     e)
    (js/console.warn (str "Couldn't resolve portal target " target))))
