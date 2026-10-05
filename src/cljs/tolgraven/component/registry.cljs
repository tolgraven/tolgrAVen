(ns tolgraven.component.registry
  "Lightweight component declarations shared by leaf views and the runtime."
  (:require [reagent.core :as r]
            [tolgraven.component.instrumentation]))

(defonce ^:private *definitions (js/WeakMap.))
(defonce *catalog (r/atom {}))

(defn component-spec [component]
  (.get *definitions (if (var? component) @component component)))

(defn register-component! [component definition]
  (.set *definitions component definition)
  (when ^boolean goog.DEBUG
    (swap! *catalog assoc [(:ns definition) (:name definition)]
           (dissoc definition :make-render)))
  component)

(defn definition [ns-name component-name options make-render]
  (let [features (->> (:features options)
                      (map #(if (keyword? %) [% true] %))
                      (remove #(false? (second %)))
                      vec)]
    {:ns ns-name :name component-name :options options :features features
     :make-render make-render}))
