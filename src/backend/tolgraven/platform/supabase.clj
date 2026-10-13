(ns tolgraven.platform.supabase
  (:require
   [clj-http.client :as http]
   [clj-http.conn-mgr :as connections]
   [clojure.data.json :as json]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as string]
   [tolgraven.config :as config]
   [tolgraven.supabase.catalog :as catalog]
   [tolgraven.media.query :as media-query]
   [tolgraven.validation :as validation]
   [tolgraven.site :as site])
  (:import
   [java.net URLEncoder]
   [org.postgresql.util PGobject]))

(defn table-definitions []
  (let [options (merge-with merge (select-keys (:modules site/config) (keys catalog/definitions))
                            (get-in config/env [:supabase :modules]))]
    (validation/check! "Supabase module options" catalog/module-options (or options {}))
    (doseq [[owner settings] options]
      (when-not (contains? catalog/definitions owner)
        (throw (ex-info "Unknown Supabase storage owner" {:owner owner})))
      (let [features (conj (set (concat (keep :feature (get catalog/definitions owner))
                                        (keys (get-in site/config [:modules owner])))) :enabled)]
        (doseq [feature (keys settings) :when (not (contains? features feature))]
          (throw (ex-info "Unknown Supabase storage feature" {:owner owner :feature feature})))))
    (let [tables (catalog/selected-tables catalog/definitions options)]
      (doseq [table tables]
        (validation/check! "Supabase table declaration" catalog/table (dissoc table :owner)))
      tables)))

(defn table-order []
  (mapv :table (filter :seed-key (table-definitions))))

(defn delete-order []
  (mapv :table (reverse (table-definitions))))

(defn table-config [table]
  (if-let [declaration (some #(when (= table (:table %)) %) (table-definitions))]
    (catalog/table-config declaration)
    (throw (ex-info "Supabase table is disabled or undeclared" {:table table}))))

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
  (or (env "SUPABASE_PUBLIC_URL")
      (env "SUPABASE_URL")
      (env "NEXT_PUBLIC_SUPABASE_URL")
      (get config/env :supabase-public-url)
      (get config/env :supabase-url)
      (throw (ex-info "Missing Supabase public URL"
                      {:env ["SUPABASE_PUBLIC_URL" "SUPABASE_URL" "NEXT_PUBLIC_SUPABASE_URL"]}))))

(defn service-key []
  (or (env "SUPABASE_SERVICE_KEY")
      (env "SUPABASE_SERVICE_ROLE_KEY")
      (get config/env :supabase-service-key)
      (get config/env :supabase-service-role-key)
      (get config/env :service-supabaseservice-key)
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

(def ^:dynamic ^:private *rest-connections nil)

(defn with-rest-connections!
  "Reuse bounded Supabase connections for a joined read stage, then close them.
   The binding follows concurrent/submit!'s bound tasks; headers stay per request."
  [f]
  (if *rest-connections
    (f)
    (let [pool (connections/make-reusable-conn-manager
                {:timeout 5
                 :threads 8
                 :default-per-route 8
                 :insecure? (insecure-rest?)})]
      (try
        (binding [*rest-connections pool] (f))
        (finally (connections/shutdown-manager pool))))))

(defn base-http-opts []
  (cond-> {:headers {"apikey" (service-key)
                    "Authorization" (str "Bearer " (service-key))}
           :throw-exceptions false
           :conn-timeout 3000
           :socket-timeout 15000
           :as :json
           :coerce :always
           :insecure? (insecure-rest?)}
    *rest-connections (assoc :connection-manager *rest-connections)))

(defn request! [method path opts]
  (let [response (http/request (-> (merge (base-http-opts)
                                           {:method method :url (rest-url path)}
                                           opts)
                                    (assoc :headers (merge (:headers (base-http-opts))
                                                           (:headers opts)))))]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info "Supabase REST request failed"
                      {:method method
                       :path path
                       :status (:status response)
                       :body (:body response)})))
    (update response :body
      (fn [body]
        (if (sequential? body) (mapv media-query/normalize-row body)
            (media-query/normalize-row body))))))

(defn ->jsonb [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str value))))

(defn prepare-row [table row]
  (let [jsonb-columns (:jsonb (table-config table))]
    (reduce-kv
     (fn [acc k v]
       (assoc acc k (if (contains? jsonb-columns k) (->jsonb v) v)))
     {}
     row)))

(defn reset-table-rest! [table]
  (let [[field value] (string/split (:delete-filter (table-config table)) #"=" 2)]
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
  (let [{:keys [seed-key order select]} (table-config table)]
    [seed-key
     (loop [offset 0 rows []]
       (let [page (:body (request! :get (name table)
                                  {:query-params {"select" select "order" order
                                                  "offset" offset "limit" 500}}))]
         (if (seq page)
           (recur (+ offset (count page)) (into rows page))
           rows)))]))

(defn fetch-seed []
  (into {}
        (map (fn [table]
               (if (jdbc-available?)
                 (let [{:keys [seed-key select order]} (table-config table)
                       sql-order (string/replace order #"\.(asc|desc)" " $1")]
                   [seed-key (jdbc/query (database-spec)
                                        [(str "select " select " from " (name table)
                                              " order by " sql-order)])])
                 (fetch-table-rest table))))
        (table-order)))
