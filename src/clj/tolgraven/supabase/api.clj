(ns tolgraven.supabase.api
  (:require
   [tolgraven.config :refer [env]]
   [tolgraven.platform.supabase :as platform]
   [tolgraven.store.contract :as contract]
   [clj-http.client :as http]
   [clojure.tools.logging :as log]
   [tolgraven.supabase.query :as query]))

(defn- environment-value [key]
  (System/getenv key))

(defn public-settings []
  (let [settings {:url (or (env :supabase-public-url) (env :supabase-url)
                           (environment-value "SUPABASE_PUBLIC_URL") (environment-value "SUPABASE_URL")
                           (environment-value "NEXT_PUBLIC_SUPABASE_URL"))
                  :anon-key (or (env :supabase-publishable-key) (environment-value "SUPABASE_PUBLISHABLE_KEY")
                                (env :supabase-anon-key) (environment-value "SUPABASE_ANON_KEY")
                                (environment-value "NEXT_PUBLIC_SUPABASE_ANON_KEY"))}
        missing (cond-> []
                  (not (seq (:url settings))) (conj "SUPABASE_PUBLIC_URL")
                  (not (seq (:anon-key settings))) (conj "SUPABASE_ANON_KEY"))]
    (when (seq missing)
      (throw (ex-info "Supabase browser configuration is missing"
                      {:status 503 :missing missing})))
    ;; Public reads need only the URL and public key. Optional metadata must
    ;; not make the entire site depend on a privileged request or Auth uptime.
    (assoc settings
           :trusted-author-ids
           (try
             (mapv :user_id (:body (platform/request! :get "auth_roles"
                                      {:query-params {"role" "eq.admins" "select" "user_id"}})))
             (catch Exception _
               (log/warn "Supabase trusted authors unavailable; links remain untrusted")
               []))
           :providers
           (try
             (:external (:body (http/get (str (:url settings) "/auth/v1/settings")
                                 {:headers {"apikey" (:anon-key settings)} :as :json
                                  :throw-exceptions false :conn-timeout 3000 :socket-timeout 5000})))
             (catch Exception _
               (log/warn "Supabase provider settings unavailable")
               {})))))

(defn settings-response []
  (try
    {:status 200 :body (public-settings)}
    (catch clojure.lang.ExceptionInfo e
      (if-let [missing (:missing (ex-data e))]
        {:status 503 :body {:error "Supabase browser configuration is missing"
                           :missing missing}}
        (throw e)))))

(defn public-query? [opts]
  (let [{:keys [path-document path-collection]} (query/normalize-query opts)
        path (or path-document path-collection)]
    (and (not (and path-document path-collection))
         (= (count path) (if path-document 2 1))
         (every? #(and (string? %) (seq %)) path)
         (query/public-read-query? opts))))

(defn query-store! [opts]
  (when-not (public-query? opts)
    (throw (ex-info "This collection is not publicly readable" {:status 403})))
  ;; Fetch only the requested public tables and columns. Never materialize the
  ;; service-key contract (which includes credentials) for a public request.
  (let [seed (into {}
                   (map (fn [{:keys [seed-key table filters select]}]
                          [seed-key
                           (loop [offset 0 rows []]
                             (let [page (:body
                                         (platform/request!
                                          :get table
                                          {:query-params
                                           (into {"select" select
                                                  "order" (:order (platform/table-config (keyword table)))
                                                  "offset" offset "limit" 500}
                                                 (map (fn [[field op value]]
                                                        [(name field) (str op "." (if (nil? value) "null" value))]))
                                                 filters)}))]
                               (if (seq page)
                                 (recur (+ offset (count page)) (into rows page))
                                 rows)))]))
                   (query/seed-load-plan opts))]
    (query/query-contract (contract/seed->contract seed) opts)))

(defn query-response [opts]
  (if (public-query? opts)
    {:status 200 :body (query-store! opts)}
    {:status 403 :body {:error "This collection is not publicly readable"}}))
