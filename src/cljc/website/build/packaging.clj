(ns website.build.packaging
  "Dependency-free packaging policy, also readable before Lein resolves deps."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn owner-enabled?
  "An authoring child is absent when its declared parent capability is absent."
  [settings declarations id]
  (loop [owner id, seen #{}]
    (cond
      (nil? owner) true
      (seen owner) (throw (ex-info "Cyclic site module ownership" {:module id}))
      (not (contains? declarations owner)) (throw (ex-info "Unknown site module owner" {:module id :owner owner}))
      (= false (get-in settings [:modules owner :enabled])) false
      :else (recur (get-in declarations [owner :site/owner]) (conj seen owner)))))

(defn- forms [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [result []]
        (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} reader)]
          (if (= ::eof form) result (recur (conj result form))))))))

(defn- declarations []
  (for [directory (.listFiles (io/file "src/frontend/website/modules"))
        :let [file (io/file directory "module.cljs")]
        :when (.isFile file)
        :let [source (forms file)
              ns-form (first source)
              spec (nth (first (filter #(and (seq? %) (= 'def (first %)) (= 'spec (second %))) source)) 2)
              storage-file (io/file directory "storage.cljc")
              tables (when (and (:storage spec) (.isFile storage-file))
                       (nth (first (filter #(and (seq? %) (= 'def (first %)) (= 'tables (second %)))
                                           (forms storage-file))) 2))]]
    (assoc spec :site/owner (:site/owner (first (filter map? (drop 2 ns-form)))) :tables tables :prefix (-> (str (second ns-form))
                                           (string/replace #"\.module$" "")
                                           (string/replace "-" "_")
                                           (string/replace "." "/")))))

(defn plan []
  (let [config (let [defaults (edn/read-string (slurp "resources/site.edn"))
                     overrides (when (.isFile (io/file "resources/site.overrides.edn"))
                                 (edn/read-string (slurp "resources/site.overrides.edn")))]
                 (merge defaults overrides
                        {:modules (merge-with merge (:modules defaults) (:modules overrides))
                         :pages (merge (:pages defaults) (:pages overrides))}))
        all (declarations)
        by-id (into {} (map (juxt :id identity)) all)
        enabled? #(owner-enabled? config by-id %)
        selected (filter #(enabled? (:id %)) all)
        disabled (map :prefix (remove #(enabled? (:id %)) all))
        selected-sql (set (concat
                           (for [{:keys [id tables]} selected
                                 table tables
                                 :when (not= false (get-in config [:modules id (:feature table)]))]
                             (:sql table))
                           (mapcat (fn [{:keys [server]}]
                                     (if (and (:sql-without-comments server)
                                              (= false (get-in config [:modules :blog :comments])))
                                       (:sql-without-comments server) (:sql server))) selected)))
        disabled-sql (remove selected-sql
                             (concat (mapcat #(keep :sql (:tables %)) all)
                                     (mapcat #(concat (get-in % [:server :sql])
                                                      (get-in % [:server :sql-without-comments])) all)))
        selected-styles (set (mapcat :styles selected))
        disabled-styles (remove selected-styles (mapcat :styles all))
        prefixes (cond-> (mapv #(str % "/") disabled)
                   (or (= false (get-in config [:modules :blog :enabled]))
                       (= false (get-in config [:modules :blog :comments])))
                   (conj "website/modules/blog/comments"))
        excluded? (fn [path] (some #(string/starts-with? path %) prefixes))
        aot (->> ["src/backend" "src/cljc" "src/frontend" "env/prod/clj"]
                 (map io/file)
                 (filter #(.exists %))
                 (mapcat file-seq)
                 (filter #(.isFile %))
                 (filter #(re-find #"\.cljc?$" (str %)))
                 (keep (fn [file]
                         (let [text (slurp file)
                               ns-name (with-open [reader (java.io.PushbackReader. (io/reader file))]
                                         (binding [*read-eval* false]
                                           (let [form (read {:read-cond :allow :features #{:clj}} reader)]
                                             (when (= 'ns (first form)) (str (second form))))))
                               path (some-> ns-name (string/replace "-" "_")
                                            (string/replace "." "/"))]
                           (when (and path (not (excluded? path))) (symbol ns-name)))))
                 distinct sort vec)]
    {:aot aot
      :codox-namespaces [(if (empty? prefixes) #"^website\."
                            (re-pattern
                              (str "^(?!"
                                   (string/join "|"
                                     (map #(str (java.util.regex.Pattern/quote
                                                  (-> % (string/replace #"/$" "")
                                                      (string/replace "/" ".")
                                                      (string/replace "_" "-")))
                                                "(?:\\.|$)") prefixes))
                                   ")website\\.")))]
      :exclusions (vec (concat
                       (map #(re-pattern (str "^" (java.util.regex.Pattern/quote %))) prefixes)
                       (map #(re-pattern (str "^docs/codox/" (java.util.regex.Pattern/quote
                                                              (-> % (string/replace "/" ".") (string/replace "_" "-"))))) prefixes)
                       (map #(re-pattern (str "^" (java.util.regex.Pattern/quote %) "$")) disabled-sql)
                       (map #(re-pattern (str "^public" (java.util.regex.Pattern/quote
                                                         (string/replace % #"\.min\.css$" ""))
                                              "(?:\\.min)?\\.css$")) disabled-styles)
                       (for [{:keys [id]} all :when (not (enabled? id))]
                         (re-pattern (str "^public/css/tolgraven/modules/icons-" (name id) "(?:\\.min)?\\.css$")))
                       [#"^public/vendor/supabase\.js(?:\.map)?$"
                        #"^supabase/(?:schema|operations)\.sql$"
                        #"^site\.overrides\.edn$"]))}))
