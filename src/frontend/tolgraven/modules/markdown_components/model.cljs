(ns tolgraven.modules.markdown-components.model
  "Data-only component arguments. Neither expressions nor imports are executable."
  (:require [clojure.string :as string]
            [tolgraven.markdown.components :as syntax]
            [tolgraven.modules.markdown-components.catalog :as catalog]))

(def binding-schema syntax/binding-schema)
(def argument-schema syntax/argument-schema)
(def invocation-schema syntax/invocation-schema)
(def public-path? syntax/public-path?)
(def decode-value syntax/decode-value)

(defn encode-value [{:keys [path literal]}]
  (if path (str "db:" (pr-str path))
      (if (and (string? literal) (not (re-find #"^(db:|json:)" literal))) literal
          (str "json:" (js/JSON.stringify (clj->js literal))))))

(defn component-name [node]
  (when (= "Component" (:name node))
    (:value (some #(when (= "component" (:name %)) %) (:attributes node)))))

(defn invocation [node]
  (try
    (let [identity (component-name node)
          attrs (syntax/literal-attributes (:attributes node))]
      (when-not (contains? catalog/by-name identity)
        (throw (js/Error. "This component is not available on this site")))
      (when (seq (:children node)) (throw (js/Error. "Use component arguments for its content")))
      {:name identity
       :arguments (into {} (map (fn [[key value]] [key (decode-value value)])) (dissoc attrs :component))})
    (catch :default error {:error (.-message error)})))

(defn attributes [arguments]
  (mapv (fn [[key value]] {:type "mdxJsxAttribute", :name (name key), :value (encode-value value)})
        (sort-by (comp name key) arguments)))

(defn defaults [entry]
  (into (if (= :positional (:mode entry)) {:args {:literal []}} {})
        (map (fn [[key value]] [key {:literal value}])) (:defaults entry)))

(defn node-attributes [{:keys [name arguments]}]
  (into [{:type "mdxJsxAttribute", :name "component", :value name}]
        (attributes arguments)))
