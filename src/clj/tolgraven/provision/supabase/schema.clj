(ns tolgraven.provision.supabase.schema
  (:require
   [clojure.java.io :as io]
   [clojure.java.jdbc :as jdbc]
   [tolgraven.platform.supabase :as supabase]
   [tolgraven.provision.supabase.config :as config]))

(defn apply-schema!
  ([] (apply-schema! nil))
  ([_]
   (if-not (supabase/jdbc-available?)
     (throw (ex-info "Schema apply requires direct Postgres access from inside the Supabase network"
                     {:hint "Run the bootstrap job on the internal Docker network and pass POSTGRES_* or SUPABASE_DB_* env."
                      :target (supabase/database-target-summary)}))
     (let [sql (str (-> config/schema-resource io/resource slurp) "\n"
                    (-> "supabase/operations.sql" io/resource slurp))]
       ;; PostgreSQL parses the complete script, including semicolons in DO blocks.
       ;; A transaction keeps a failed bootstrap from leaving a partial schema.
       (jdbc/with-db-transaction [tx (supabase/database-spec)]
         (jdbc/db-do-commands tx sql))
       {:applied? true}))))
