(ns tolgraven.build.modules
  "Read module declarations without loading their browser implementation."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [tolgraven.build.icons :as icons]
            [tolgraven.build.site :as site]))

(def root "src/frontend/tolgraven/modules")

(defn declaration [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (let [forms (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} reader)))
            ns-form (first forms)
            [_ _ spec] (first (filter #(and (seq? %) (= 'def (first %)) (= 'spec (second %))) forms))
            attrs (first (filter map? (drop 2 ns-form)))
            storage (:storage spec)
            pages (:pages spec)
            server (:server spec)
            aliases (into {} (for [clause (drop 2 ns-form)
                                   :when (and (seq? clause) (= :require (first clause)))
                                   entry (rest clause)
                                   :when (vector? entry)
                                   :let [alias (second (drop-while #(not= :as %) entry))]
                                   :when alias]
                               [alias (first entry)]))
            id (:id spec)
            content (:content spec)
            styles (:styles spec)
            icon-dependencies (:icons spec)
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
        (when (and (contains? spec :icons) (not (icons/valid-icons? icon-dependencies)))
          (throw (ex-info "Module :icons must be literal brands/name or solid/name strings"
                          {:file (str file)})))
        (when (and (contains? spec :ssr-styles)
                   (not (#{:initial :deferred} ssr-styles)))
          (throw (ex-info "Module :ssr-styles must be literal :initial or :deferred"
                          {:file (str file)})))
        (when (and storage (not (and (symbol? storage) (namespace storage))))
          (throw (ex-info "Module :storage must reference a portable table declaration"
                          {:file (str file)})))
        (cond-> {:id id :entry (second ns-form) :depends-on dependencies}
          (seq content) (assoc :content content)
          (seq styles) (assoc :styles styles)
          (seq icon-dependencies) (assoc :icons icon-dependencies)
          server (assoc :server (update server :routes #(if (seq? %) (second %) %)))
          pages (assoc :pages (symbol (str (get aliases (symbol (namespace pages))
                                               (namespace pages))) (name pages)))
          storage (assoc :storage (symbol (str (get aliases (symbol (namespace storage))
                                                   (namespace storage)))
                                          (name storage)))
          ssr-styles (assoc :ssr-styles ssr-styles))))))

(defn all-declarations
  ([] (all-declarations root))
  ([directory]
   (->> (.listFiles (io/file directory))
        (map #(io/file % "module.cljs"))
        (filter #(.isFile %))
        (sort-by str)
        (mapv declaration))))

(defn select-declarations [declarations settings]
  (let [settings (site/validate! settings)
        ids (map :id declarations)
        unknown (remove (set ids) (keys (:modules settings)))
        selected (filterv #(not= false (get-in settings [:modules (:id %) :enabled])) declarations)
        selected-ids (set (map :id selected))]
    (when (seq unknown) (throw (ex-info "Unknown site modules" {:modules (vec unknown)})))
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Duplicate module IDs" {:ids ids})))
    (doseq [{:keys [id storage]} declarations
            :let [tables (when (and storage (seq (dissoc (get-in settings [:modules id]) :enabled)))
                           (require (symbol (namespace storage)))
                           (var-get (requiring-resolve storage)))
                  allowed (conj (set (keep :feature tables)) :enabled)]
            option (keys (get-in settings [:modules id]))
            :when (not (allowed option))]
      (throw (ex-info "Unknown site module option" {:module id :option option})))
    (doseq [{:keys [id depends-on server]} selected
            dependency (concat (when (not= id :main) depends-on) (:requires server))
            :when (not (selected-ids dependency))]
      (throw (ex-info "Disabled or missing module dependency" {:module id :dependency dependency})))
    (doseq [{:keys [id server]} selected
            [owner features] (:requires-features server)
            feature features
            :when (= false (get-in settings [:modules owner feature]))]
      (throw (ex-info "Disabled module feature dependency" {:module id :owner owner :feature feature})))
    selected))

(defn discover
  ([] (discover root))
  ([directory]
   (vec (remove #(= :main (:id %))
                (select-declarations (all-declarations directory) (site/settings))))))

(defn icon-definitions []
  (icons/definitions (conj (discover) (declaration (io/file root "main/module.cljs")))))

(defn bundles []
  (into (sorted-map)
        (map (fn [{:keys [id entry depends-on]}]
               [id {:entries [entry] :depends-on depends-on}]))
        (discover)))

(defn- track-declarations! [env declarations]
  (site/track! env)
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
  (let [declarations (discover)
        icon-definitions (icon-definitions)]
    (track-declarations! &env declarations)
    (when (:ns &env)
      (let [read-resource! (requiring-resolve 'shadow.resource/slurp-resource)
            entries (set (map #(str (-> (str (:entry %))
                                       (string/replace "-" "_")
                                       (string/replace "." "/")) ".cljs") declarations))]
        (doseq [file (:sources (meta icon-definitions))
                :let [path (icons/source-path file)]
                :when (and path (not (entries path)))]
          (read-resource! &env path))))
    (into {} (map (fn [{:keys [id styles depends-on ssr-styles]}]
                   [id (cond-> {:paths (cond-> (vec styles)
                                           (seq (get icon-definitions id))
                                           (conj (icons/stylesheet-path id)))
                                :depends-on depends-on}
                         ssr-styles (assoc :ssr-styles ssr-styles))])) declarations)))

(defmacro storage-definitions []
  (let [declarations (conj (discover) (declaration (io/file root "main/module.cljs")))]
    (track-declarations! &env declarations)
    (into {}
          (keep (fn [{:keys [id storage]}]
                  (when storage
                    (require (symbol (namespace storage)) :reload)
                    (let [tables (var-get (requiring-resolve storage))]
                      (when-not (vector? tables)
                        (throw (ex-info "Module storage declaration must be a vector"
                                        {:module id :storage storage})))
                      (when (:ns &env)
                        ((requiring-resolve 'shadow.resource/slurp-resource)
                         &env (str (-> (namespace storage)
                                       (string/replace "-" "_")
                                       (string/replace "." "/")) ".cljc")))
                      [id (filterv #(or (nil? (:feature %))
                                        (site/enabled? id (:feature %))) tables)]))))
          declarations)))

(defmacro server-definitions []
  (let [declarations (conj (discover) (declaration (io/file root "main/module.cljs")))]
    (track-declarations! &env declarations)
    (list 'quote
          (into {} (keep (fn [{:keys [id server pages]}]
                          (when (or server pages) [id (cond-> server pages (assoc :pages pages))])))
                declarations))))

(defn configure-site
  {:shadow.build/stage :configure}
  [state]
  ;; Initialize tracking before Shadow expands independent namespaces in parallel.
  (require 'shadow.resource)
  (let [ids (conj (mapv :id (discover)) :main)
        features (concat (map #(keyword "module" (name %)) ids)
                         (for [[id options] (:modules (site/settings))
                               [feature value] options
                               :when (and value (not= :enabled feature))]
                           (keyword "feature" (str (name id) "-" (name feature))))
                         ;; Optional comments default to enabled like other site capabilities.
                         (when (site/enabled? :blog :comments) [:feature/blog-comments]))]
    (-> state
        (update-in [:compiler-options :reader-features] #(into (or % #{}) features))
        (update-in [:shadow.build/config :compiler-options :reader-features]
                   #(into (or % #{}) features))
        (update :extra-config-files into [(io/file "resources/site.edn")
                                           (io/file "resources/site.overrides.edn")]))))

(defn clean-site-output
  {:shadow.build/stage :configure}
  [state]
  (let [{:keys [output-dir output-to]} (:shadow.build/config state)
        stamp (if output-dir (io/file output-dir ".site-config.edn")
                  (io/file (str output-to ".site-config.edn")))
        selection [(site/settings) (:mode state)]
        previous (when (.isFile stamp) (edn/read-string (slurp stamp)))]
    ;; A slim site must not serve chunks from an earlier configuration. Node
    ;; targets own one file, not their whole parent (which other targets share).
    (when (and previous (not= previous selection))
      (if output-dir
        (doseq [file (reverse (file-seq (io/file output-dir)))] (io/delete-file file true))
        (doseq [path [output-to (str output-to ".revision")]]
          (io/delete-file path true))))
    (io/make-parents stamp)
    (spit stamp (pr-str selection))
    state))

(defn check-site-sources
  {:shadow.build/stage :compile-prepare}
  [state]
  (let [disabled (for [{:keys [id entry]} (all-declarations)
                       :when (not (site/enabled? id))]
                   (str (-> (namespace (symbol (str entry) "spec"))
                            (string/replace #"\.module$" "")
                            (string/replace "-" "_")
                            (string/replace "." "/")) "/"))]
    (doseq [id (:build-sources state)
            :let [{:keys [resource-name] :as source} (get-in state [:sources id])]
            :when (and resource-name
                       (or (some #(string/starts-with? resource-name %) disabled)
                           (and (not (site/enabled? :blog :comments))
                                (string/starts-with? resource-name "tolgraven/modules/blog/comments."))))]
      (throw (ex-info "Disabled capability entered the source graph"
                      (select-keys source [:ns :resource-name]))))
    state))

(defn- storage-data [{:keys [id storage]}]
  (when storage
    (require (symbol (namespace storage)) :reload)
    (let [tables (var-get (requiring-resolve storage))]
      (when-not (vector? tables)
        (throw (ex-info "Module storage declaration must be a vector" {:module id})))
      tables)))

(defn retention-data
  ([] (retention-data (site/settings)))
  ([settings]
  (let [enabled? (fn
                   ([id] (not= false (get-in settings [:modules id :enabled])))
                   ([id feature] (and (not= false (get-in settings [:modules id :enabled]))
                                      (not= false (get-in settings [:modules id feature])))))
        all (all-declarations)
        tables (mapcat storage-data all)
        enabled-tables (mapcat (fn [{:keys [id] :as module}]
                                (when (enabled? id)
                                  (filter #(or (nil? (:feature %)) (enabled? id (:feature %)))
                                          (storage-data module)))) all)
        table-ids (set (map :table enabled-tables))
        all-sql (distinct (concat (keep :sql tables) (mapcat #(get-in % [:server :sql]) all)))
        enabled-sql (distinct (concat (keep :sql enabled-tables)
                                      (mapcat (fn [{:keys [id server]}]
                                                (when (enabled? id)
                                                  (if (and (:sql-without-comments server)
                                                           (not (enabled? :blog :comments)))
                                                    (:sql-without-comments server) (:sql server)))) all)))
        functions (fn [resources]
                    (set (mapcat #(map second (re-seq #"(?i)create or replace function public\.([a-z0-9_]+)"
                                                      (slurp (io/resource %)))) resources)))]
    {:tables (mapv (comp name :table) (remove #(table-ids (:table %)) tables))
     :functions (vec (sort (remove (functions enabled-sql) (functions all-sql))))
     :avatars? (not (enabled? :user))})))

(defmacro retention-definitions []
  (track-declarations! &env (all-declarations))
  (retention-data))

(defmacro content-definitions []
  (let [declarations (conj (discover) (declaration (io/file root "main/module.cljs")))]
    (track-declarations! &env declarations)
    (into {} (map (juxt :id #(vec (:content %)))) declarations)))
