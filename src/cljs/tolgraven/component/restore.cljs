(ns tolgraven.component.restore
  "First-page restoration context; no loading/motion bypass without ready data."
  (:require [reagent.core :as r]
            [clojure.string :as string]))

(defonce *context (r/atom {}))
(defn page-key [] (if (exists? js/location) (str (.-pathname js/location) (.-search js/location)) "/"))
(defn begin! [{:keys [hydrate? back?]}]
  (reset! *context {:hydrate? (boolean hydrate?) :back? (boolean back?) :page (page-key)}))
(defn skip-enter? []
  (and (= (:page @*context) (page-key)) (or (:hydrate? @*context) (:back? @*context))))
(defn navigate! [path]
  (when (and (:page @*context) (not= (first (string/split path #"\?")) (first (string/split (:page @*context) #"\?")))) (reset! *context {})))
(defn back-navigation? []
  (or (= "back_forward" (some-> js/performance (.getEntriesByType "navigation") (aget 0) .-type))
      (= 2 (some-> js/performance .-navigation .-type))))
(defonce listener
  (when (exists? js/window)
    (.addEventListener js/window "pageshow"
                       (fn [event] (when (.-persisted event) (begin! {:back? true}))))))
