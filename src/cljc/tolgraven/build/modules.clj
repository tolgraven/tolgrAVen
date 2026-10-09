(ns tolgraven.build.modules
  "Read module declarations without loading their browser implementation."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

(def root "src/frontend/tolgraven/modules")

(defn declaration [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (let [forms (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} reader)))
            ns-form (first forms)
            [_ _ spec] (first (filter #(and (seq? %) (= 'def (first %)) (= 'spec (second %))) forms))
            attrs (first (filter map? (drop 2 ns-form)))
            id (:id spec)
            styles (:styles spec)
            ssr-styles (:ssr-styles spec)
            dependencies (get attrs :bundle/depends-on #{:main})]
        (when-not (and (= 'ns (first ns-form)) (symbol? (second ns-form))
                       (keyword? id) (set? dependencies) (every? keyword? dependencies))
          (throw (ex-info "Module needs a namespace, literal spec :id and keyword bundle dependencies"
                          {:file (str file)})))
        (when-not (or (nil? styles)
                      (and (vector? styles) (every? string? styles)))
          (throw (ex-info "Module :styles must be a literal vector of stylesheet paths"
                          {:file (str file)})))
        (when (and (contains? spec :ssr-styles)
                   (not (#{:initial :deferred} ssr-styles)))
          (throw (ex-info "Module :ssr-styles must be literal :initial or :deferred"
                          {:file (str file)})))
        (cond-> {:id id :entry (second ns-form) :depends-on dependencies}
          (seq styles) (assoc :styles styles)
          ssr-styles (assoc :ssr-styles ssr-styles))))))

(defn discover
  ([] (discover root))
  ([directory]
   (let [declarations (->> (.listFiles (io/file directory))
                           (map #(io/file % "module.cljs"))
                           (filter #(.isFile %))
                           (sort-by str)
                           (mapv declaration))
         ids (map :id declarations)]
     (when-not (= (count ids) (count (set ids)))
       (throw (ex-info "Duplicate module IDs" {:ids ids})))
     (doseq [{:keys [id depends-on]} declarations
             dependency depends-on
             :when (and (not= id :main) (not (contains? (set ids) dependency)))]
       (throw (ex-info "Unknown bundle dependency" {:module id :dependency dependency})))
     (vec (remove #(= :main (:id %)) declarations)))))

(defn bundles []
  (into (sorted-map)
        (map (fn [{:keys [id entry depends-on]}]
               [id {:entries [entry] :depends-on depends-on}]))
        (discover)))

(defn- track-declarations! [env declarations]
  (when (:ns env)
    ;; Shadow retains analysis between compiler processes too. Track each source
    ;; resource so changed metadata invalidates both baked runtime inventories.
    ;; JVM runtime expansion must not require the compiler dependency.
    (let [read-resource! (requiring-resolve 'shadow.resource/slurp-resource)]
      (doseq [{:keys [entry]} declarations]
        (read-resource! env (str (-> (str entry)
                                    (string/replace "-" "_")
                                    (string/replace "." "/"))
                                ".cljs"))))))

(defmacro loadables []
  (let [declarations (discover)]
    (track-declarations! &env declarations)
    `(hash-map
       ~@(mapcat (fn [{:keys [id entry]}]
                   [id `(shadow.lazy/loadable ~(symbol (str entry) "spec"))])
                 declarations))))

(defmacro style-definitions []
  ;; Bake this inventory into both runtimes. Packaged servers have no source tree.
  (let [declarations (discover)]
    (track-declarations! &env declarations)
    (into {} (map (fn [{:keys [id styles depends-on ssr-styles]}]
                   [id (cond-> {:paths (vec styles)
                                :depends-on depends-on}
                         ssr-styles (assoc :ssr-styles ssr-styles))])) declarations)))
