(ns website.provision.supabase.import
  (:require
   [clojure.data.json :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [next.jdbc.transaction :as transaction]
   [clojure.string :as string]
   [website.platform.supabase :as supabase]
   [website.provision.supabase.config :as config]
   [website.provision.supabase.schema :as schema]
   [website.store.contract :as contract]
   [website.supabase.interop :as interop]))

(defn reset-data!
  ([] (reset-data! nil))
  ([db]
   (let [tables (supabase/delete-order)]
     (when (seq tables)
       (if (supabase/jdbc-available?)
         (jdbc/execute!
          (or db (supabase/database-spec))
          [(str "truncate table " (string/join ", " (map name tables))
               " restart identity")])
         (doseq [table tables]
           (supabase/reset-table-rest! table)))))))

(defn import-seed!
  ([seed] (import-seed! nil seed))
  ([db seed]
   (if (supabase/jdbc-available?)
     (doseq [table (supabase/table-order)
             :let [rows (get seed (:seed-key (supabase/table-config table)))]]
       (when (seq rows)
         ;; Preserve java.jdbc's joined transaction semantics, including callers
         ;; supplying a db spec and rows with different optional columns.
         (binding [transaction/*nested-tx* :ignore]
           (jdbc/with-transaction [tx (or db (supabase/database-spec))]
             (doseq [row rows]
               (sql/insert! tx table (supabase/prepare-row table row)))))))
     (doseq [table (supabase/table-order)
             :let [rows (get seed (:seed-key (supabase/table-config table)))]]
       (supabase/upsert-rest! table rows)))
   seed))

(defn load-export! [path]
  (-> path slurp (json/read-str :key-fn keyword)))

(defn export->seed [path]
  (contract/firebase-export->seed (load-export! path)))

(defn fetch-contract []
  (contract/seed->contract (supabase/fetch-seed)))

(defn install-and-import!
  ([export-path] (install-and-import! nil export-path))
  ([_ export-path]
   ;; Parse before destructive work; schema, replacement and the legacy private
   ;; document backfill must commit together or leave the original data intact.
   (let [seed (export->seed export-path)]
     (when-not (supabase/jdbc-available?)
       (throw (ex-info "Full import requires direct Postgres access" {})))
     (binding [transaction/*nested-tx* :ignore]
       (jdbc/with-transaction [tx (supabase/database-spec)]
         (schema/apply-schema! tx)
         (reset-data! tx)
         (import-seed! tx seed)
         (schema/apply-schema! tx)))
     seed)))

(defn- upsert-rows! [db table rows]
  (if db
    (doseq [row rows
            :let [prepared (supabase/prepare-row table row)
                  columns (vec (keys prepared))
                  column-names (map name columns)
                  sql (str "insert into " (name table) " ("
                           (string/join "," column-names) ") values ("
                           (string/join "," (repeat (count columns) "?"))
                           ") on conflict (" (:on-conflict (supabase/table-config table))
                           ") do update set "
                           (string/join "," (map #(str % "=excluded." %) column-names)))]]
      (jdbc/execute! db (into [sql] (map prepared columns))))
    (supabase/upsert-rest! table rows)))

(defn import-scope!
  ([scope export-path] (import-scope! nil scope export-path))
  ([_ scope export-path]
   (let [collections (or (get config/import-scopes scope)
                         (throw (ex-info "Unknown import scope"
                                         {:scope scope
                                          :known-scopes (sort (keys config/import-scopes))})))
         current (fetch-contract)
         incoming (contract/firebase-export->contract (load-export! export-path))
         updated (reduce
                  (fn [contract' collection]
                    (assoc contract' collection (get incoming collection)))
                  current
                  collections)]
     ;; Refuse implicit row deletion, and never truncate unrelated runtime
     ;; tables (private documents and the vote ledger are not in legacy exports).
     (let [before (contract/contract->seed current)
           after (contract/contract->seed updated)
           changes (mapv (fn [table] [table (interop/changed-rows before after table)])
                         (supabase/table-order))
           apply! (fn [db]
                    (doseq [[table rows] changes :when (seq rows)]
                      (upsert-rows! db table rows)))]
       (if (supabase/jdbc-available?)
         (binding [transaction/*nested-tx* :ignore]
           (jdbc/with-transaction [tx (supabase/database-spec)]
             (apply! tx)
             (schema/apply-schema! tx)))
         (apply! nil)))
     {:scope scope
      :collections (sort collections)})))
