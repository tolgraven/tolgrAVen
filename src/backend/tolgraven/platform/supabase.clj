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

(def ^:private connection-config
  {:jdbc {:database-url {:keys [:supabase-database-url :supabase-db-url]}
          :host {:keys [:postgres-hostname :postgres-host :pghost], :required? true}
          :database {:keys [:postgres-db :postgres-database :pgdatabase], :required? true}
          :user {:keys [:postgres-user :pguser], :required? true}
          :password {:keys [:postgres-password :pgpassword], :required? true}
          :port {:keys [:postgres-port :pgport], :default "5432"}
          :sslmode {:keys [:supabase-db-sslmode :postgres-sslmode], :default "disable"}}
   :rest {:url {:keys [:supabase-public-url :supabase-url :next-public-supabase-url]
                :error "Missing Supabase public URL"}
          :service-key {:keys [:supabase-service-key :supabase-service-role-key :service-supabaseservice-key]
                        :error "Missing Supabase service key"}
          :insecure? {:keys [:supabase-insecure-tls]
                      :default false
                      :parse #(contains? #{true 1 "1" "true" "TRUE" "yes" "YES"} %)}}})

(defn- connection-settings [group]
  (reduce-kv
    (fn [settings key {:keys [keys default parse]}]
      (let [value (if-some [configured (first (keep #(get config/env %) keys))]
                    configured
                    default)]
        (assoc settings key (if parse (parse value) value))))
    {}
    (get connection-config group)))

(defn- expected-config [group]
  (reduce-kv (fn [expected key definition] (assoc expected key (:keys definition)))
             {}
             (get connection-config group)))

(defn- required-setting [group key]
  (or (get (connection-settings group) key)
      (let [{:keys [keys error]} (get-in connection-config [group key])]
        (throw (ex-info error {:config-keys keys})))))

(defn- jdbc-components-configured? [settings]
  (every? (fn [[key {:keys [required?]}]]
            (or (not required?) (get settings key)))
          (:jdbc connection-config)))

(defn- encode-query-value [value]
  (URLEncoder/encode (str value) "UTF-8"))

(defn- jdbc-url-from-components [{:keys [host database user password port sslmode] :as settings}]
  (when (jdbc-components-configured? settings)
    (let [params {"user" user
                  "password" password
                  "sslmode" sslmode}
          query-string (->> params
                            (map (fn [[key value]]
                                   (str key "=" (encode-query-value value))))
                            (string/join "&"))]
      (str "jdbc:postgresql://" host ":" port "/" database "?" query-string))))

(defn database-url []
  (let [settings (connection-settings :jdbc)]
    (or (:database-url settings)
        (jdbc-url-from-components settings))))

(defn database-target-summary []
  (let [settings (connection-settings :jdbc)]
    (cond
      (:database-url settings)
      {:source :database-url, :configured? true}

      (jdbc-components-configured? settings)
      (merge {:source :components, :configured? true}
             (select-keys settings [:host :port :database :user :sslmode]))

      :else
      {:source :missing
       :configured? false
       :expected-config (expected-config :jdbc)})))

(defn jdbc-available? []
  (boolean (database-url)))

(defn database-spec []
  (or (database-url)
      (throw (ex-info "Missing Supabase database URL"
                      {:config-keys (expected-config :jdbc)}))))

(defn rest-base-url []
  (required-setting :rest :url))

(defn service-key []
  (required-setting :rest :service-key))

(defn insecure-rest? []
  (:insecure? (connection-settings :rest)))

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
