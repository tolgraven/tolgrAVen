(ns tolgraven.supabase.interop
  (:require
   [clj-http.client :as http]
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

(def ^:private table-order
  [:site_users
   :auth_roles
   :blog_posts
   :blog_comments
   :chat_messages
   :service_configs
   :store_documents])

(def ^:private delete-order
  [:auth_roles
   :blog_comments
   :blog_posts
   :chat_messages
   :service_configs
   :store_documents
   :site_users])

(def ^:private delete-filters
  {:site_users "id=not.is.null"
   :auth_roles "role=not.is.null"
   :blog_posts "id=not.is.null"
   :blog_comments "id=not.is.null"
   :chat_messages "message_id=not.is.null"
   :service_configs "service=not.is.null"
   :store_documents "collection=not.is.null"})

(def ^:private table-config
  {:site_users {:seed-key :users
                :order "id.asc"}
   :auth_roles {:seed-key :roles
                :order "role.asc,user_id.asc"}
   :blog_posts {:seed-key :blog_posts
                :order "id.asc"}
   :blog_comments {:seed-key :blog_comments
                   :order "ts.asc,id.asc"}
   :chat_messages {:seed-key :chat_messages
                   :order "message_id.asc"}
   :service_configs {:seed-key :service_configs
                     :order "service.asc"}
   :store_documents {:seed-key :store_documents
                     :order "collection.asc,doc_id.asc"}})

(declare fetch-contract)

(defn database-url []
  (or (System/getenv "SUPABASE_DATABASE_URL")
      (System/getenv "SUPABASE_DB_URL")))

(defn database-spec []
  (or (database-url)
      (throw (ex-info "Missing Supabase database URL" {:env ["SUPABASE_DATABASE_URL" "SUPABASE_DB_URL"]}))))

(defn- rest-base-url []
  (or (System/getenv "SUPABASE_PUBLIC_URL")
      (System/getenv "SUPABASE_URL")
      (System/getenv "NEXT_PUBLIC_SUPABASE_URL")
      (throw (ex-info "Missing Supabase public URL"
                      {:env ["SUPABASE_PUBLIC_URL" "SUPABASE_URL" "NEXT_PUBLIC_SUPABASE_URL"]}))))

(defn- service-key []
  (or (System/getenv "SUPABASE_SERVICE_KEY")
      (System/getenv "SUPABASE_SERVICE_ROLE_KEY")
      (throw (ex-info "Missing Supabase service key"
                      {:env ["SUPABASE_SERVICE_KEY" "SUPABASE_SERVICE_ROLE_KEY"]}))))

(defn- insecure-rest? []
  (contains? #{"1" "true" "TRUE" "yes" "YES"}
             (or (System/getenv "SUPABASE_INSECURE_TLS")
                 "")))

(defn- rest-url [path]
  (str (string/replace (rest-base-url) #"/+$" "")
       "/rest/v1/"
       path))

(defn- base-http-opts []
  {:headers {"apikey" (service-key)
             "Authorization" (str "Bearer " (service-key))}
   :throw-exceptions false
   :as :json
   :coerce :always
   :insecure? (insecure-rest?)})

(defn- request! [method path opts]
  (let [response (http/request (merge (base-http-opts)
                                      {:method method
                                       :url (rest-url path)}
                                      opts))]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info "Supabase REST request failed"
                      {:method method
                       :path path
                       :status (:status response)
                       :body (:body response)})))
    response))

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

(defn- jdbc-available? []
  (boolean (database-url)))

(defn apply-schema!
  ([] (apply-schema! nil))
  ([_]
   (if-not (jdbc-available?)
     (throw (ex-info "Schema apply requires direct Postgres access or an exposed SQL admin API"
                     {:hint "Use resources/supabase/schema.sql from within the Supabase network, or expose a SQL admin endpoint temporarily."}))
     (let [sql (-> schema-resource io/resource slurp)
           statements (split-sql sql)]
       (doseq [statement statements]
         (jdbc/db-do-commands (database-spec) statement))
       (count statements)))))

(defn- reset-table-rest! [table]
  (request! :delete
            (name table)
            {:query-params {"select" "*"
                            (first (string/split (delete-filters table) #"="))
                            (second (string/split (delete-filters table) #"="))}
             :headers {"Prefer" "return=minimal"}}))

(defn reset-data!
  ([] (reset-data! nil))
  ([_]
   (if (jdbc-available?)
     (jdbc/db-do-commands
      (database-spec)
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
     (doseq [table delete-order]
       (reset-table-rest! table)))))

(defn- upsert-rest! [table rows]
  (when (seq rows)
    (request!
     :post
     (name table)
     {:headers {"Prefer" "resolution=merge-duplicates,return=minimal"}
      :content-type :json
      :form-params rows
      :query-params {"on_conflict"
                     (case table
                       :site_users "id"
                       :auth_roles "role,user_id"
                       :blog_posts "id"
                       :blog_comments "id"
                       :chat_messages "message_id"
                       :service_configs "service"
                       :store_documents "collection,doc_id")}})))

(defn import-seed!
  ([seed] (import-seed! nil seed))
  ([_ seed]
   (if (jdbc-available?)
     (doseq [[table rows] [[:site_users (:users seed)]
                           [:auth_roles (:roles seed)]
                           [:blog_posts (:blog_posts seed)]
                           [:blog_comments (:blog_comments seed)]
                           [:chat_messages (:chat_messages seed)]
                           [:service_configs (:service_configs seed)]
                           [:store_documents (:store_documents seed)]]]
       (when (seq rows)
         (jdbc/insert-multi! (database-spec) table (map prepare-row rows))))
     (doseq [table table-order
             :let [rows (get seed (:seed-key (table-config table)))]]
       (upsert-rest! table rows)))
   seed))

(defn load-export! [path]
  (-> path slurp (json/read-str :key-fn keyword)))

(defn export->seed [path]
  (shape/firebase-export->seed (load-export! path)))

(defn install-and-import!
  ([export-path] (install-and-import! nil export-path))
  ([_ export-path]
   (apply-schema!)
   (reset-data!)
   (->> export-path
        export->seed
        (import-seed!))))

(defn import-scope!
  ([scope export-path] (import-scope! nil scope export-path))
  ([_ scope export-path]
   (let [collections (or (get import-scopes scope)
                         (throw (ex-info "Unknown import scope"
                                         {:scope scope
                                          :known-scopes (sort (keys import-scopes))})))
         current (fetch-contract)
         incoming (shape/firebase-export->contract (load-export! export-path))
         updated (reduce
                  (fn [contract collection]
                    (assoc contract collection (get incoming collection)))
                  current
                  collections)]
     (reset-data!)
     (import-seed! (shape/contract->seed updated))
     {:scope scope
      :collections (sort collections)})))

(defn- fetch-table-rest [table]
  (let [{:keys [seed-key order]} (table-config table)
        response (request! :get
                           (name table)
                           {:query-params {"select" "*"
                                           "order" order}})]
    [seed-key (:body response)]))

(defn fetch-seed
  ([] (fetch-seed nil))
  ([_]
   (if (jdbc-available?)
     {:users (jdbc/query (database-spec) ["select * from site_users order by id"])
      :roles (jdbc/query (database-spec) ["select role, user_id from auth_roles order by role, user_id"])
      :blog_posts (jdbc/query (database-spec) ["select * from blog_posts order by id"])
      :blog_comments (jdbc/query (database-spec) ["select * from blog_comments order by ts, id"])
      :chat_messages (jdbc/query (database-spec) ["select * from chat_messages order by message_id"])
      :service_configs (jdbc/query (database-spec) ["select * from service_configs order by service"])
      :store_documents (jdbc/query (database-spec) ["select * from store_documents order by collection, doc_id"])}
     (into {}
           (map fetch-table-rest)
           table-order))))

(defn fetch-contract
  ([] (fetch-contract nil))
  ([_]
   (shape/seed->contract (fetch-seed))))

(defn write-document!
  ([path data merge-fields]
   (write-document! nil path data merge-fields))
  ([_ path data merge-fields]
   (let [contract (fetch-contract)
         updated (store/set-document contract path data merge-fields)]
     (reset-data!)
     (import-seed! (shape/contract->seed updated))
     (get-in updated path))))
