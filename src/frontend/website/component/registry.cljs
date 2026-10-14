(ns website.component.registry
  "Lightweight component declarations shared by leaf views and the runtime."
  (:require [website.react :as r]
            [website.validation.runtime :as validation]
            [website.schema.declarations :as schemas]
            [website.component.instrumentation]
            [website.component.features :as features]))

(defonce ^:private *definitions (js/WeakMap.))
(defonce *catalog (r/atom {}))
(defonce ^:private *public-components (r/atom {}))
(defonce ^:private *public-resolvers (atom {}))

(defn register-public-resolvers!
  "Legacy render functions retain their identity; Shadow owns their module boundary."
  [resolvers]
  (swap! *public-resolvers merge resolvers)
  nil)

(defn public-component
  "Resolve a canonical identity after its ordinary lazy module has registered."
  [identity]
  (or (get @*public-components identity)
      (when-let [{:keys [resolve definition]} (get @*public-resolvers identity)]
        (when-let [component (resolve)]
          {:component component, :definition definition}))))

(defn component-spec [component]
  (.get *definitions (if (var? component) @component component)))

(defn register-component! [component definition]
  (.set *definitions component definition)
  (when (get-in definition [:options :public?])
    (swap! *public-components assoc (str (:ns definition) "/" (:name definition))
           {:component component, :definition definition}))
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


(defn normalize-args
  "Expand feature-vector specs at the component boundary. Domain arguments
   and explicit :spec false declarations retain their original values."
  [{:keys [options]} args]
  (if (and (not= false (:spec options)) (vector? (first args)))
    (into [{:features (first args)}] (rest args))
    args))

(defn validate-args! [{:keys [ns name options]} args]
  (let [args' (normalize-args {:options options} args)]
    (when @validation/*enabled?
      (when-let [schema (:args-schema options)]
        (validation/check! (str ns "/" name " arguments") schema (vec args')))
      (when-let [schema (:spec-schema options)]
        (validation/check! (str ns "/" name " spec")
                           (schemas/extend-spec schema) (first args'))))
    args'))
