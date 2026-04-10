(ns tolgraven.supabase.interop
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string]
   [taoensso.timbre :as timbre]
   [tolgraven.supabase.shape :as shape]
   [tolgraven.supabase.store :as store])
  (:import
   [org.postgresql.util PGobject]))

(def schema-resource "supabase/schema.sql")

(def import-scopes
  {"auth" #{"auth"}
   "blog" #{"blog-comments" "blog-post-ids" "blog-posts"}
   "chat" #{"chat"}
   "gpt" #{"gpt" "gpt-threads"}
   "services" #{"imagor" "instagram" "secrets" "strapi" "strava" "typesense"}
   "users" #{"users"}})

(declare fetch-contract)

(defn database-url []
  (or (System/getenv "SUPABASE_DATABASE_URL")
      (System/getenv "SUPABASE_DB_URL")))

(defn database-spec []
  (or (database-url)
      (throw (ex-info "Missing Supabase database URL" {:env ["SUPABASE_DATABASE_URL" "SUPABASE_DB_URL"]}))))

(defn- ->jsonb [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str value))))

(defn- prepare-row [row]
  (reduce-kv
   (fn [acc k v]
     (assoc acc
            k
            (if (contains? #{:comments :voted :raw :path :config :data} k)
              (->jsonb v)
              v)))
   {}
   row))

(defn- split-sql [sql]
  (->> (string/split sql #";\s*(?:\r?\n|$)")
       (map string/trim)
       (remove string/blank?)))

(defn apply-schema!
  ([] (apply-schema! (database-spec)))
  ([db]
   (let [sql (-> schema-resource io/resource slurp)
         statements (split-sql sql)]
     (doseq [statement statements]
       (jdbc/db-do-commands db statement))
     (count statements))))

(defn reset-data!
  ([] (reset-data! (database-spec)))
  ([db]
   (jdbc/db-do-commands
    db
    (str "truncate table "
         (string/join ", "
                      ["auth_roles"
                       "blog_comments"
                       "blog_posts"
                       "chat_messages"
                       "service_configs"
                       "store_documents"
                       "site_users"])
         " restart identity cascade"))))

(defn import-seed!
  ([seed] (import-seed! (database-spec) seed))
  ([db seed]
   (doseq [[table rows] [[:site_users (:users seed)]
                         [:auth_roles (:roles seed)]
                         [:blog_posts (:blog_posts seed)]
                         [:blog_comments (:blog_comments seed)]
                         [:chat_messages (:chat_messages seed)]
                         [:service_configs (:service_configs seed)]
                         [:store_documents (:store_documents seed)]]]
     (when (seq rows)
       (jdbc/insert-multi! db table (map prepare-row rows))))
   seed))

(defn load-export! [path]
  (-> path slurp (json/read-str :key-fn keyword)))

(defn export->seed [path]
  (shape/firebase-export->seed (load-export! path)))

(defn install-and-import!
  ([export-path] (install-and-import! (database-spec) export-path))
  ([db export-path]
   (apply-schema! db)
   (reset-data! db)
   (->> export-path
        export->seed
        (import-seed! db))))

(defn import-scope!
  ([scope export-path] (import-scope! (database-spec) scope export-path))
  ([db scope export-path]
   (let [collections (or (get import-scopes scope)
                         (throw (ex-info "Unknown import scope"
                                         {:scope scope
                                          :known-scopes (sort (keys import-scopes))})))
         current (fetch-contract db)
         incoming (shape/firebase-export->contract (load-export! export-path))
         updated (reduce
                  (fn [contract collection]
                    (assoc contract collection (get incoming collection)))
                  current
                  collections)]
     (apply-schema! db)
     (reset-data! db)
     (import-seed! db (shape/contract->seed updated))
     {:scope scope
      :collections (sort collections)})))

(defn fetch-seed
  ([] (fetch-seed (database-spec)))
  ([db]
   {:users (jdbc/query db ["select * from site_users order by id"])
    :roles (jdbc/query db ["select role, user_id from auth_roles order by role, user_id"])
    :blog_posts (jdbc/query db ["select * from blog_posts order by id"])
    :blog_comments (jdbc/query db ["select * from blog_comments order by ts, id"])
    :chat_messages (jdbc/query db ["select * from chat_messages order by message_id"])
    :service_configs (jdbc/query db ["select * from service_configs order by service"])
    :store_documents (jdbc/query db ["select * from store_documents order by collection, doc_id"])}))

(defn fetch-contract
  ([] (fetch-contract (database-spec)))
  ([db]
   (shape/seed->contract (fetch-seed db))))

(defn write-document!
  ([path data merge-fields]
   (write-document! (database-spec) path data merge-fields))
  ([db path data merge-fields]
   (let [contract (fetch-contract db)
         updated (store/set-document contract path data merge-fields)]
     (reset-data! db)
     (import-seed! db (shape/contract->seed updated))
     (get-in updated path))))
