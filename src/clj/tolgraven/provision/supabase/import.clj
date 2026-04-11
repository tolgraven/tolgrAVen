(ns tolgraven.provision.supabase.import
  (:require
   [clojure.data.json :as json]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string]
   [tolgraven.platform.supabase :as supabase]
   [tolgraven.provision.supabase.config :as config]
   [tolgraven.provision.supabase.schema :as schema]
   [tolgraven.store.contract :as contract]))

(defn reset-data!
  ([] (reset-data! nil))
  ([_]
   (if (supabase/jdbc-available?)
     (jdbc/db-do-commands
      (supabase/database-spec)
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
  ([_ seed]
   (if (supabase/jdbc-available?)
     (doseq [[table rows] [[:site_users (:users seed)]
                           [:auth_roles (:roles seed)]
                           [:blog_posts (:blog_posts seed)]
                           [:blog_comments (:blog_comments seed)]
                           [:chat_messages (:chat_messages seed)]
                           [:service_configs (:service_configs seed)]
                           [:store_documents (:store_documents seed)]]]
       (when (seq rows)
         (jdbc/insert-multi! (supabase/database-spec)
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
   (schema/apply-schema!)
   (reset-data!)
   (->> export-path
        export->seed
        (import-seed!))))

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
     (reset-data!)
     (import-seed! (contract/contract->seed updated))
     {:scope scope
      :collections (sort collections)})))
