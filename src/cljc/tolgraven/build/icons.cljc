(ns tolgraven.build.icons
  "Collect literal icon dependencies without loading application namespaces."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            #?(:bb [edamame.core :as parser]
               :clj [clojure.tools.reader :as reader])))

(def source-roots ["src/frontend" "src/cljc"])
(def icon-pattern #"(?:brands|solid)/[a-z0-9-]+")
(defn valid-icons? [value]
  (and (vector? value) (every? #(and (string? %) (re-matches icon-pattern %)) value)))

(defn source-path [file]
  (some #(when (string/starts-with? (str file) (str % "/"))
           (subs (str file) (inc (count %)))) source-roots))

(defn- forms [file]
  ;; Skip unrelated namespaces, but still reject nonliteral icon declarations.
  (when (string/includes? (slurp file) ":icons")
    #?(:bb (parser/parse-string-all (slurp file)
                                   {:all true
                                    :read-eval false
                                    :read-cond :allow
                                    :features #{:cljs}
                                    :auto-resolve (constantly 'icon.declaration)
                                    :readers {'js identity}})
       :clj (with-open [stream (java.io.PushbackReader. (io/reader file))]
              (binding [reader/*read-eval* false
                        reader/*data-readers* (assoc reader/*data-readers* 'js identity)]
                (let [options {:eof ::eof
                               :read-cond :allow
                               :features #{:cljs}}
                      ns-form (reader/read options stream)
                      *aliases (atom {})]
                  (walk/prewalk
                    (fn [value]
                      (when (and (vector? value) (symbol? (first value)))
                        (when-let [alias (second (drop-while #(not= :as %) value))]
                          (swap! *aliases assoc alias (first value))))
                      value) ns-form)
                  (binding [reader/*alias-map* @*aliases]
                    (cons ns-form
                          (doall (take-while #(not= ::eof %)
                                            (repeatedly #(reader/read options stream))))))))))))

(defn definitions
  "Return owner -> sorted icon names. Common icons satisfy feature declarations;
   metadata retains source resources for Shadow's incremental invalidation."
  ([modules] (definitions modules source-roots))
  ([modules roots]
   (let [ids (set (map :id modules))
         prefixes (into {} (map (fn [{:keys [id entry]}]
                                 [(str "src/frontend/"
                                       (-> (str entry)
                                           (string/replace "-" "_")
                                           (string/replace "." "/")
                                           (string/replace #"module$" ""))) id])) modules)
         *icons (atom {})
         *sources (atom #{})]
     (doseq [root roots
             file (sort-by str (file-seq (io/file root)))
             :when (and (.isFile file) (re-find #"\.cljs?$|\.cljc$" (str file)))
             form (forms file)]
       (walk/prewalk
         (fn [value]
           (when (and (map? value) (contains? value :icons))
             (when-not (valid-icons? (:icons value))
               (throw (ex-info "Icon dependencies must be literal brands/name or solid/name strings"
                               {:file (str file)})))
             (let [owner (or (:module value)
                             (some (fn [[prefix id]]
                                     (when (string/starts-with? (str file) prefix) id)) prefixes)
                             :main)]
               (when-not (ids owner)
                 (throw (ex-info "Unknown icon module owner" {:file (str file), :module owner})))
               (swap! *sources conj (str file))
               (swap! *icons update owner (fnil into #{}) (:icons value))))
           value) form))
     (let [common (get @*icons :main #{})]
       (with-meta
         (into (sorted-map)
               (keep (fn [[id names]]
                       (let [names (if (= :main id) names (remove common names))]
                         (when (seq names) [id (vec (sort names))])))) @*icons)
         {:sources (vec (sort @*sources))})))))

(defn stylesheet-path [module]
  (when-not (= :main module)
    (str "/css/tolgraven/modules/icons-" (name module) ".min.css")))
