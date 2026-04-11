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
                     {:hint "Run the bootstrap command from a container or host that can reach the internal Postgres service."}))
     (let [sql (-> config/schema-resource io/resource slurp)
           statements (split-sql sql)]
       (doseq [statement statements]
         (jdbc/db-do-commands (supabase/database-spec) statement))
       {:statements (count statements)}))))
