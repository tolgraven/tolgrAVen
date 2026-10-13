(ns tolgraven.modules.markdown-editor.catalog
  "Rich source documentation belongs only to the authoring SDK owner."
  (:require [clojure.string :as string]
            [tolgraven.components.content.layout :as layout]
            [tolgraven.component.registry :as registry]
            [tolgraven.schema.dsl :as dsl])
  (:require-macros [tolgraven.build.authoring :refer [catalog]]))

(def entries (catalog))
(def by-name (into {} (map (juxt :name identity)) entries))

(defn map-fields [schema]
  (let [schema' (dsl/->malli schema)]
    (when (vector? schema')
      (let [tail (rest schema')
            children (if (map? (first tail)) (rest tail) tail)]
        (case (first schema')
          :map (filter vector? children)
          :merge (mapcat map-fields children)
          :and (mapcat map-fields children)
          :maybe (map-fields (first children))
          :cat (map-fields (first children))
          :tuple (map-fields (first children))
          [])))))

(defn arguments [{:keys [name schema mode]}]
  (let [options (get-in (registry/public-component name) [:definition :options])
        fields (or (seq (map-fields (:spec-schema options)))
                   (seq (map-fields (:args-schema options)))
                   (seq (map-fields schema)))
        parameters (if (and (= :map mode) (or fields (= [:map] schema)))
                     (mapv (fn [[key & values]]
                             {:key (keyword (string/replace (cljs.core/name key) #"^\?" ""))
                              :optional? (or (string/starts-with? (cljs.core/name key) "?")
                                             (:optional (first values)))
                              :schema (last values)}) fields)
                     [{:key :args, :optional? false, :schema [:vector :any]}])]
    (into parameters (map (fn [key] {:key key, :optional? true, :schema :string})) layout/attribute-fields)))

(defn search [query]
  (let [words (remove string/blank? (string/split (string/lower-case query) #"\s+"))]
    (filterv (fn [entry]
               (let [text (string/lower-case (str (:name entry) " " (:doc entry) " " (:schema entry) " " (:parameters entry)))]
                 (every? #(string/includes? text %) words))) entries)))
