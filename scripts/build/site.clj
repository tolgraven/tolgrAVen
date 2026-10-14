(ns build.site
  "Export the same declaration-derived schema plan used by the JVM provisioner."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cheshire.core :as json]
            [website.build.modules :as modules]
            [website.build.site :as site]
            [website.site-catalog :as catalog]
            [website.supabase.catalog :as storage]
            [website.supabase.schema-plan :as plan]
            [website.supabase.retention :as retention]))

(defn page-names [modules]
  ;; Inspect portable route data without loading the declarations' JVM adapters.
  (vec (sort (distinct
               (mapcat (fn [{:keys [pages]}]
                         (when pages
                           (let [resource (str (-> (namespace pages)
                                                    (string/replace "-" "_")
                                                    (string/replace "." "/")) ".cljc")]
                             (with-open [reader (java.io.PushbackReader. (io/reader (io/resource resource)))]
                               (binding [*read-eval* false]
                                 (let [forms (take-while #(not= ::eof %)
                                                        (repeatedly #(read {:eof ::eof :read-cond :allow
                                                                            :features #{:clj}} reader)))]
                                   (keep #(when (and (map? %) (keyword? (:name %))) (:name %))
                                         (tree-seq coll? seq (vec forms))))))))) modules)))))

(defn manifest-for [settings]
  (let [settings (site/validate! settings)
        all (modules/all-declarations)
        selected (modules/select-declarations all settings)
        definitions (into {} (keep (fn [{:keys [id storage]}]
                                    (when storage
                                      (require (symbol (namespace storage)))
                                      [id (var-get (requiring-resolve storage))]))) all)
        tables (storage/selected-tables definitions (:modules settings))
        server (into {} (map (juxt :id :server)) selected)
        comments? (and (not= false (get-in settings [:modules :blog :enabled]))
                       (not= false (get-in settings [:modules :blog :comments])))
        resources (plan/schema-resources tables server comments?)]
    {:config settings
     :style-reference {:path "resources/scss/shell/_vars.scss"
                       :source-base64 (.encodeToString (java.util.Base64/getEncoder)
                                                       (.getBytes (slurp (io/resource "scss/shell/_vars.scss")) "UTF-8"))}
     :pages (page-names all)
     :available-modules (mapv (fn [{:keys [id entry content depends-on server]}]
                                {:id id
                                 :entry entry
                                 :requires (vec (sort (distinct
                                                      (concat (when (not= id :main) depends-on)
                                                              (:requires server)
                                                              (when (some #(nil? (:feature %)) (get definitions id)) [:supabase])))))
                                 :requires-options (into {} (for [feature (distinct (keep :feature (get definitions id)))]
                                                             [feature [:supabase]]))
                                 :requires-features (:requires-features server)
                                 :content (vec content)
                                 :options (vec (sort (cond-> (set (keep :feature (get definitions id)))
                                                      (not (#{:main :coercion} id)) (conj :enabled))))} ) all)
     :modules (mapv :id selected)
     :tables (mapv (comp name :table) tables)
     :content (vec (distinct (mapcat :content selected)))
     :sql-resources resources
     :sql (str (string/join "\n" (map #(slurp (io/resource %)) resources))
               "\n" (retention/sql (modules/retention-data settings))
               "\nnotify pgrst,'reload schema';\n")}))

(defn manifest [] (manifest-for (site/settings)))

(defn -main [& [format]]
  (let [value (manifest)]
    (if (= format "sql") (print (:sql value))
        (println (json/generate-string value)))))
