(ns website.provision.supabase.schema
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.java.jdbc :as jdbc]
   [website.platform.supabase :as supabase]
   [website.site-catalog :as catalog]
   [website.site :as site]
   [website.supabase.schema-plan :as plan]
   [website.supabase.retention :as retention]))

(defn schema-resources []
  (plan/schema-resources (supabase/table-definitions) catalog/modules
                         (site/enabled? :blog :comments)))

(defn schema-sql []
  (str (string/join "\n" (map #(slurp (io/resource %)) (schema-resources)))
       "\n" (retention/sql retention/disabled)
       "\nnotify pgrst,'reload schema';\n"))

(defn apply-schema!
  ([] (apply-schema! nil))
  ([db]
   (if-not (supabase/jdbc-available?)
     (throw (ex-info "Schema apply requires direct Postgres access from inside the Supabase network"
                     {:hint "Run the bootstrap job on the internal Docker network and pass POSTGRES_* or SUPABASE_DB_* env."
                      :target (supabase/database-target-summary)}))
     (let [sql (schema-sql)]
       ;; PostgreSQL parses the complete script, including semicolons in DO blocks.
       ;; A transaction keeps a failed bootstrap from leaving a partial schema.
       (jdbc/with-db-transaction [tx (or db (supabase/database-spec))]
         (jdbc/db-do-commands tx sql))
       {:applied? true}))))
