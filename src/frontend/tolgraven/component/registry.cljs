(ns tolgraven.component.registry
  "Lightweight component declarations shared by leaf views and the runtime."
  (:require [reagent.core :as r]
            [tolgraven.validation.runtime :as validation]
            [tolgraven.schema.declarations :as schemas]
            [tolgraven.component.instrumentation]
            [tolgraven.component.features :as features]))

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
  (validation/check! (str ns-name "/" component-name)
                     (schemas/extend-component (:schema options)) options)
  {:ns ns-name
   :name component-name
   :options options
   :features (features/entries (:features options))
   :make-render make-render})


(defn validate-args! [{:keys [ns name options]} args]
  (when @validation/*enabled?
    (when-let [schema (:args-schema options)]
      (validation/check! (str ns "/" name " arguments") schema (vec args)))
    (when-let [schema (:spec-schema options)]
      (validation/check! (str ns "/" name " spec")
                         (schemas/extend-spec schema) (first args))))
  args)
