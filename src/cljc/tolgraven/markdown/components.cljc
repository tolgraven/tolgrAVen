(ns tolgraven.markdown.components
  "Portable data syntax shared by authoring, published rendering and ready-media lookup."
  (:require [clojure.string :as string]
            #?(:clj [clojure.edn :as reader] :cljs [cljs.reader :as reader])
            #?(:clj [cheshire.core :as json])))

(def binding-schema [:map [:path [:vector {:min 1} [:or :keyword :string :int]]]])
(def argument-schema [:or [:map [:literal :any]] binding-schema])
(def invocation-schema [:map [:name :string] [:arguments [:map-of :keyword argument-schema]]])

(defn public-path? [path]
  (and (vector? path) (<= 1 (count path) 12)
       (every? #(or (keyword? %) (string? %) (and (int? %) (<= 0 %))) path)
       (or (= :content (first path))
           (= :content-components (first path))
           (and (= :options (first path)) (= :theme (second path))))))

(defn decode-value [text]
  (cond
    (string/starts-with? text "db:")
    (let [path (reader/read-string {:readers {}, :default (fn [_ _] (throw (ex-info "Tagged code is not data" {})))}
                                   (subs text 3))]
      (when-not (public-path? path) (throw (ex-info "Choose a public content or theme path" {})))
      {:path path})
    (string/starts-with? text "json:")
    {:literal #?(:clj (json/parse-string (subs text 5) true)
                 :cljs (js->clj (js/JSON.parse (subs text 5)) :keywordize-keys true))}
    :else {:literal text}))

(defn literal-attributes [attributes]
  (let [names (map :name attributes)]
    (when-not (= (count names) (count (distinct names)))
      (throw (ex-info "Duplicate component argument" {})))
    (into {} (map (fn [{:keys [type name value]}]
                    (when-not (and (= "mdxJsxAttribute" type) (string? name) (string? value))
                      (throw (ex-info "Use quoted data values; expressions are not executable" {})))
                    [(keyword name) value])) attributes)))

(defn decode-entities [value]
  ;; These are attribute entities, never executable markup. Keep unknown entities
  ;; literal; the browser's full JSX parser already supplies decoded attributes.
  (string/replace value #"&(?:quot|apos|amp|lt|gt);"
                  {"&quot;" "\"", "&apos;" "'", "&amp;" "&", "&lt;" "<", "&gt;" ">"}))

(defn component-node
  "Recognize only an entire self-closing Component with distinct quoted attributes."
  [source]
  (when-let [[_ body] (re-matches #"<Component([\s\S]*)/\s*>" source)]
    (loop [remaining body, attributes []]
      (if (string/blank? remaining)
        (let [node {:name "Component", :attributes attributes}]
          (try (literal-attributes attributes) node
               (catch #?(:clj Exception :cljs :default) _ nil)))
        (when-let [[matched name double single]
                   (re-find #"^\s+([A-Za-z_][A-Za-z0-9_:-]*)\s*=\s*(?:\"([^\"]*)\"|'([^']*)')" remaining)]
          (recur (subs remaining (count matched))
                 (conj attributes {:type "mdxJsxAttribute"
                                   :name name
                                   :value (decode-entities (or double single))})))))))

(defn component-nodes [text]
  (keep component-node
        (take 128 (re-seq #"<Component(?=\s|/|>)(?:[^'\">]|\"[^\"]*\"|'[^']*')*>" (or text "")))))

(defn literal-sources [value]
  (loop [pending [[value 0]], sources [], visited 0]
    (if (or (empty? pending) (>= visited 256)) sources
      (let [[node depth] (peek pending)
            pending' (pop pending)
            src (when (map? node) (get node :src))
            children (when (< depth 8) (cond (map? node) (vals node) (sequential? node) node))]
        (recur (into pending' (map #(vector % (inc depth))) (take 256 children))
               (cond-> sources (and (string? src) (seq src)) (conj src))
               (inc visited))))))

(defn image-sources
  "Collect bounded literal component sources. Ready DB manifests, not authored data, grant metadata."
  [text]
  (vec (distinct
         (mapcat (fn [node]
                   (let [attributes (literal-attributes (:attributes node))]
                     (when (string? (:component attributes))
                       (mapcat (fn [[key value]]
                                 (when (and (not= :component key) (<= (count value) 32768))
                                   (try
                                     (let [{:keys [literal]} (decode-value value)]
                                       (if (= :src key)
                                         (when (string? literal) [literal])
                                         (literal-sources literal)))
                                     (catch #?(:clj Exception :cljs :default) _ [])))) attributes))))
                 (component-nodes text)))))
