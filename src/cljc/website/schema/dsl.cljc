(ns website.schema.dsl
  "Pure schema shorthand expansion, independent of the Malli interpreter."
  (:require [clojure.string :as string]))

(defn- shorthand? [value]
  (and (keyword? value)
       (> (count (name value)) 1)
       (string/starts-with? (name value) "?")))

(defn- unprefix [value]
  (keyword (namespace value) (subs (name value) 1)))

(declare ->malli)

(defn- properties [props]
  ;; Registry values are schemas; defaults, dispatch values and other properties
  ;; are ordinary data and must retain their exact keys and values.
  (if (map? (:registry props))
    (update props :registry
            #(into (empty %) (map (fn [[key schema]] [key (->malli schema)])) %))
    props))

(defn- entry [optional-keys? value]
  (if-not (vector? value)
    value
    (let [[key & tail] value
          props? (and (seq tail) (or (map? (first tail)) (nil? (first tail))))
          optional? (and optional-keys? (shorthand? key))
          head (cond-> [(if optional? (unprefix key) key)]
                 (or props? optional?)
                 (conj (cond-> (if props? (properties (first tail)) {})
                         optional? (assoc :optional true))))
          children (if props? (rest tail) tail)]
      (with-meta (into head (map ->malli children)) (meta value)))))

(defn ->malli
  "Expand :?type / [:?type ...] to [:maybe ...] and :?map-key to an
   optional entry with the original key. Preserve native Malli syntax, literal
   values, properties, predicates, schema objects and reference identities."
  [schema]
  (cond
    (shorthand? schema) [:maybe (unprefix schema)]
    (vector? schema)
    (let [[type & tail] schema
          maybe? (shorthand? type)
          type (if maybe? (unprefix type) type)
          props? (and (seq tail) (or (map? (first tail)) (nil? (first tail))))
          children (if props? (rest tail) tail)
          head (cond-> [type] props? (conj (properties (first tail))))
          children (cond
                     (#{:map :multi :orn :catn :altn} type)
                     (map (partial entry (= :map type)) children)

                     ;; These children are values, functions, patterns or ref IDs.
                     (#{:enum := :not= :> :>= :< :<= :fn :re :ref} type) children

                     (= :select-keys type)
                     (cons (->malli (first children)) (rest children))

                     :else (map ->malli children))
          expanded (with-meta (into head children) (meta schema))]
      (if maybe? [:maybe expanded] expanded))
    :else schema))
