(ns tolgraven.component.registry
  "Lightweight component declarations shared by leaf views and the runtime.")

(defonce ^:private *definitions (js/WeakMap.))

(defn component-spec [component]
  (.get *definitions (if (var? component) @component component)))

(defn register-component! [component definition]
  (.set *definitions component definition)
  component)

(defn definition [ns-name component-name options make-render]
  (let [features (->> (:features options)
                      (map #(if (keyword? %) [% true] %))
                      (remove #(false? (second %)))
                      vec)]
    {:ns ns-name :name component-name :options options :features features
     :make-render make-render}))
