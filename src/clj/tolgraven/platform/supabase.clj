(ns tolgraven.platform.supabase
  (:require
   [clj-http.client :as http]
   [clojure.data.json :as json]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string])
  (:import
   [java.net URLEncoder]
   [org.postgresql.util PGobject]))

(def table-order
  [:site_users
   :auth_roles
   :blog_posts
   :blog_comments
   :chat_messages
   :service_configs
   :store_documents])

(def delete-order
  [:auth_roles
   :blog_comments
   :blog_posts
   :chat_messages
   :service_configs
   :store_documents
   :site_users])

(def delete-filters
  {:site_users "id=not.is.null"
   :auth_roles "role=not.is.null"
   :blog_posts "id=not.is.null"
   :blog_comments "id=not.is.null"
   :chat_messages "message_id=not.is.null"
   :service_configs "service=not.is.null"
   :store_documents "collection=not.is.null"})

(def table-config
  {:site_users {:seed-key :users
                :order "id.asc"
                :on-conflict "id"}
   :auth_roles {:seed-key :roles
                :order "role.asc,user_id.asc"
                :on-conflict "role,user_id"}
   :blog_posts {:seed-key :blog_posts
                :order "id.asc"
                :on-conflict "id"}
   :blog_comments {:seed-key :blog_comments
                   :order "ts.asc,id.asc"
                   :on-conflict "id"}
   :chat_messages {:seed-key :chat_messages
                   :order "message_id.asc"
                   :on-conflict "message_id"}
   :service_configs {:seed-key :service_configs
                     :order "service.asc"
                     :on-conflict "service"}
   :store_documents {:seed-key :store_documents
                     :order "collection.asc,doc_id.asc"
                     :on-conflict "collection,doc_id"}})

(defn- env [k]
  (System/getenv k))

(defn- encode-query-value [value]
  (URLEncoder/encode (str value) "UTF-8"))

(defn- jdbc-url-from-components []
  (let [host (or (env "POSTGRES_HOSTNAME")
                 (env "POSTGRES_HOST")
                 (env "PGHOST"))
        db-name (or (env "POSTGRES_DB")
                    (env "POSTGRES_DATABASE")
                    (env "PGDATABASE"))
        user (or (env "POSTGRES_USER")
                 (env "PGUSER"))
        password (or (env "POSTGRES_PASSWORD")
                     (env "PGPASSWORD"))]
    (when (and host db-name user password)
      (let [port (or (env "POSTGRES_PORT")
                     (env "PGPORT")
                     "5432")
            sslmode (or (env "SUPABASE_DB_SSLMODE")
                        (env "POSTGRES_SSLMODE")
                        "disable")
            params {"user" user
                    "password" password
                    "sslmode" sslmode}
            query-string (->> params
                              (map (fn [[k v]]
                                     (str k "=" (encode-query-value v))))
                              (string/join "&"))]
        (str "jdbc:postgresql://" host ":" port "/" db-name "?" query-string)))))

(defn database-url []
  (or (env "SUPABASE_DATABASE_URL")
      (env "SUPABASE_DB_URL")
      (jdbc-url-from-components)))

(defn database-target-summary []
  (let [direct-url (or (env "SUPABASE_DATABASE_URL")
                       (env "SUPABASE_DB_URL"))]
    (cond
      direct-url
      {:source :database-url
       :configured? true}

      (jdbc-url-from-components)
      {:source :components
       :configured? true
       :host (or (env "POSTGRES_HOSTNAME")
                 (env "POSTGRES_HOST")
                 (env "PGHOST"))
       :port (or (env "POSTGRES_PORT")
                 (env "PGPORT")
                 "5432")
       :database (or (env "POSTGRES_DB")
                     (env "POSTGRES_DATABASE")
                     (env "PGDATABASE"))
       :user (or (env "POSTGRES_USER")
                 (env "PGUSER"))
       :sslmode (or (env "SUPABASE_DB_SSLMODE")
                    (env "POSTGRES_SSLMODE")
                    "disable")}

      :else
      {:source :missing
       :configured? false
       :expected-env ["SUPABASE_DATABASE_URL"
                      "SUPABASE_DB_URL"
                      "POSTGRES_HOSTNAME"
                      "POSTGRES_DB"
                      "POSTGRES_USER"
                      "POSTGRES_PASSWORD"]})))

(defn jdbc-available? []
  (boolean (database-url)))

(defn database-spec []
  (or (database-url)
      (throw (ex-info "Missing Supabase database URL"
                      {:env ["SUPABASE_DATABASE_URL"
                             "SUPABASE_DB_URL"
                             "POSTGRES_HOSTNAME"
                             "POSTGRES_DB"
                             "POSTGRES_USER"
                             "POSTGRES_PASSWORD"]}))))

(defn rest-base-url []
  (or (System/getenv "SUPABASE_PUBLIC_URL")
      (System/getenv "SUPABASE_URL")
      (System/getenv "NEXT_PUBLIC_SUPABASE_URL")
      (throw (ex-info "Missing Supabase public URL"
                      {:env ["SUPABASE_PUBLIC_URL" "SUPABASE_URL" "NEXT_PUBLIC_SUPABASE_URL"]}))))

(defn service-key []
  (or (System/getenv "SUPABASE_SERVICE_KEY")
      (System/getenv "SUPABASE_SERVICE_ROLE_KEY")
      (throw (ex-info "Missing Supabase service key"
                      {:env ["SUPABASE_SERVICE_KEY" "SUPABASE_SERVICE_ROLE_KEY"]}))))

(defn insecure-rest? []
  (contains? #{"1" "true" "TRUE" "yes" "YES"}
             (or (System/getenv "SUPABASE_INSECURE_TLS")
                 "")))

(defn rest-url [path]
  (str (string/replace (rest-base-url) #"/+$" "")
       "/rest/v1/"
       path))

(defn base-http-opts []
  {:headers {"apikey" (service-key)
             "Authorization" (str "Bearer " (service-key))}
   :throw-exceptions false
   :as :json
   :coerce :always
   :insecure? (insecure-rest?)})

(defn request! [method path opts]
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

(defn ->jsonb [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str value))))

(def jsonb-columns
  #{:comments :voted :raw :path :config :data})

(defn prepare-row [row]
  (reduce-kv
   (fn [acc k v]
     (assoc acc
            k
            (if (contains? jsonb-columns k)
              (->jsonb v)
              v)))
   {}
   row))

(defn reset-table-rest! [table]
  (let [[field value] (string/split (delete-filters table) #"=" 2)]
    (request! :delete
              (name table)
              {:query-params {"select" "*"
                              field value}
               :headers {"Prefer" "return=minimal"}})))

(defn upsert-rest! [table rows]
  (when (seq rows)
    (request!
     :post
     (name table)
     {:headers {"Prefer" "resolution=merge-duplicates,return=minimal"}
      :content-type :json
      :form-params rows
      :query-params {"on_conflict" (:on-conflict (table-config table))}})))

(defn fetch-table-rest [table]
  (let [{:keys [seed-key order]} (table-config table)
        response (request! :get
                           (name table)
                           {:query-params {"select" "*"
                                           "order" order}})]
    [seed-key (:body response)]))

(defn fetch-seed []
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
          table-order)))
