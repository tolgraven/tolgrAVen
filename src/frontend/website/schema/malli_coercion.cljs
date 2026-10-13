(ns website.schema.malli-coercion
  "Reitit's frontend protocol backed by Malli, without server API documentation.
   Page routers use string parameters and query encoding; Ring keeps its adapter."
  (:require [website.schema.registry]
            [website.schema.dsl :as dsl]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [malli.util :as mu]
            [reitit.coercion :as coercion]))

(def coercion
  (reify coercion/Coercion
    (-get-name [_] :malli)
    (-get-options [_] {:strip-extra-keys false
                      :error-keys #{:type :in :humanized}})
    (-get-apidocs [_ specification _]
      (throw (ex-info "Page coercion does not generate API documentation"
                      {:specification specification})))
    (-get-model-apidocs [_ specification _ _]
      (throw (ex-info "Page coercion does not generate API documentation"
                      {:specification specification})))
    (-compile-model [_ models _]
      (reduce mu/merge (map dsl/->malli models)))
    (-open-model [_ schema] schema)
    (-encode-error [_ error]
      (-> error
          (assoc :humanized (me/humanize error {:wrap :message}))
          (select-keys [:type :in :humanized])))
    (-request-coercer [_ type schema]
      (when schema
        (when-not (= type :string)
          (throw (ex-info "Page parameters require string coercion" {:type type})))
        (let [schema* (m/schema (dsl/->malli schema))
              decode (m/decoder schema* mt/string-transformer)
              valid? (m/validator schema*)
              explain (m/explainer schema*)]
          (fn [value _format]
            (let [transformed (decode value)]
              (if (valid? transformed)
                transformed
                (coercion/map->CoercionError
                  (assoc (explain transformed) :transformed transformed))))))))
    (-response-coercer [_ _]
      (throw (ex-info "Response coercion belongs to the Ring router" {})))
    (-query-string-coercer [_ schema]
      (when schema
        (let [encode (m/encoder (mu/open-schema (dsl/->malli schema)) mt/string-transformer)]
          (fn [value _format] (encode value)))))))
