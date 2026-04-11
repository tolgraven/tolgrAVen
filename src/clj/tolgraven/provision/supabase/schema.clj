(ns tolgraven.provision.supabase.schema
  (:require
   [clojure.java.io :as io]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string]
   [tolgraven.platform.supabase :as supabase]
   [tolgraven.provision.supabase.config :as config]))

(defn split-sql [sql]
  (->> (string/split sql #";\s*(?:\r?\n|$)")
       (map string/trim)
       (remove string/blank?)))

(defn apply-schema!
  ([] (apply-schema! nil))
  ([_]
   (if-not (supabase/jdbc-available?)
     (throw (ex-info "Schema apply requires direct Postgres access from inside the Supabase network"
                     {:hint "Run the bootstrap job on the internal Docker network and pass POSTGRES_* or SUPABASE_DB_* env."
                      :target (supabase/database-target-summary)}))
     (let [sql (-> config/schema-resource io/resource slurp)
           statements (split-sql sql)]
       (doseq [statement statements]
         (jdbc/db-do-commands (supabase/database-spec) statement))
       {:statements (count statements)}))))
