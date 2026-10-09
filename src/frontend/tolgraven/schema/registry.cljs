(ns tolgraven.schema.registry
  "Malli's custom registry removes unused schema constructors from releases.
   Install before owners compose schemas; development keeps the full registry."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(when (= mr/type "custom")
  ;; These are Malli's documented extension constructors, not another validator.
  ;; Keep sequence/predicate types available for declaration and argument schemas.
  (mr/set-default-registry!
    (merge (m/predicate-schemas)
           (m/comparator-schemas)
           (m/type-schemas)
           (m/sequence-schemas)
           {:and (m/-and-schema)
            :or (m/-or-schema)
            :map (m/-map-schema)
            :map-of (m/-map-of-schema)
            :vector (m/-collection-schema {:type :vector
                                          :pred vector?
                                          :empty []})
            :sequential (m/-collection-schema {:type :sequential
                                              :pred sequential?})
            :set (m/-collection-schema {:type :set
                                       :pred set?
                                       :empty #{}
                                       :in (fn [_ value] value)})
            :enum (m/-enum-schema)
            :maybe (m/-maybe-schema)
            :tuple (m/-tuple-schema)
            :multi (m/-multi-schema)
            :re (m/-re-schema false)
            :fn (m/-fn-schema)
            :ref (m/-ref-schema)
            :schema (m/-schema-schema nil)
            :malli.core/schema (m/-schema-schema {:raw true})})))
