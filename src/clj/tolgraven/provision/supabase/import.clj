(ns tolgraven.provision.supabase.import
  (:require
   [clojure.data.json :as json]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string]
   [tolgraven.platform.supabase :as supabase]
   [tolgraven.provision.supabase.config :as config]
   [tolgraven.provision.supabase.schema :as schema]
   [tolgraven.store.contract :as contract]
   [tolgraven.supabase.interop :as interop]))

(defn reset-data!
  ([] (reset-data! nil))
  ([db]
   (if (supabase/jdbc-available?)
     (jdbc/db-do-commands
      (or db (supabase/database-spec))
      (str "truncate table "
           (string/join ", "
                        ["auth_roles"
                         "blog_comments"
                         "blog_posts"
                         "chat_messages"
                         "service_configs"
                         "store_documents"
                         "site_users"])
           " restart identity cascade"))
     (doseq [table supabase/delete-order]
       (supabase/reset-table-rest! table)))))

(defn import-seed!
  ([seed] (import-seed! nil seed))
  ([db seed]
   (if (supabase/jdbc-available?)
     (doseq [[table rows] [[:site_users (:users seed)]
                           [:auth_roles (:roles seed)]
                           [:blog_posts (:blog_posts seed)]
                           [:blog_comments (:blog_comments seed)]
                           [:chat_messages (:chat_messages seed)]
                           [:service_configs (:service_configs seed)]
                           [:store_documents (:store_documents seed)]]]
       (when (seq rows)
         (jdbc/insert-multi! (or db (supabase/database-spec))
                             table
                             (map supabase/prepare-row rows))))
     (doseq [table supabase/table-order
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
     (jdbc/with-db-transaction [tx (supabase/database-spec)]
       (schema/apply-schema! tx)
       (reset-data! tx)
       (import-seed! tx seed)
       (schema/apply-schema! tx))
     seed)))

(defn- upsert-rows! [db table rows]
  (if db
    (doseq [row rows
            :let [prepared (supabase/prepare-row row)
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
                         supabase/table-order)
           apply! (fn [db]
                    (doseq [[table rows] changes :when (seq rows)]
                      (upsert-rows! db table rows)))]
       (if (supabase/jdbc-available?)
         (jdbc/with-db-transaction [tx (supabase/database-spec)]
           (apply! tx)
           (schema/apply-schema! tx))
         (apply! nil)))
     {:scope scope
      :collections (sort collections)})))
