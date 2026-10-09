(ns tolgraven.schema.page-coercion
  "Reitit compiles stable dispatchers; the deferred adapter owns Malli coercers."
  (:require [reitit.coercion :as coercion]
            #?(:dev [tolgraven.schema.malli-coercion :as malli]
               :ssr [tolgraven.schema.malli-coercion :as malli])))

(defonce *adapter (atom #?(:dev malli/coercion :ssr malli/coercion :default nil)))
(defonce ^:private *coercers (atom {}))
(defn ready? [] (some? @*adapter))
(defn install! [adapter] (reset! *coercers {}) (reset! *adapter adapter))
(defn- dispatcher [kind type schema]
  (fn [value format]
    (if-let [adapter @*adapter]
      (let [key [kind type schema]
            coercer (or (get @*coercers key)
                        (let [coercer (case kind
                                        :request (coercion/-request-coercer adapter type schema)
                                        :query (coercion/-query-string-coercer adapter schema))]
                          (swap! *coercers assoc key coercer)
                          coercer))]
        (if coercer (coercer value format) value))
      ;; Initial SSR supplies typed parameters. Other navigation waits for the
      ;; adapter before controllers run; Reitit's native URL encoding stays usable.
      value)))
(def coercion
  (reify coercion/Coercion
    (-get-name [_] :malli)
    (-get-options [_] {:strip-extra-keys false})
    (-get-apidocs [_ _ _] nil)
    (-get-model-apidocs [_ _ _ _] nil)
    (-compile-model [_ models _] (into [:merge] models))
    (-open-model [_ schema] schema)
    (-encode-error [_ error] (coercion/-encode-error @*adapter error))
    (-request-coercer [_ type schema] (when schema (dispatcher :request type schema)))
    (-response-coercer [_ _] nil)
    (-query-string-coercer [_ schema] (when schema (dispatcher :query nil schema)))))
