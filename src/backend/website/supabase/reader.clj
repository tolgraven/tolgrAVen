(ns website.supabase.reader
  "JVM transport for the same public query plans used by the browser adapter."
  (:require [website.config :as config]
            [website.site :as site]
            [website.validation :as validation]
            [website.supabase.schema :as schema]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [website.platform.supabase :as platform]
            [website.concurrent :as concurrent]
            [website.supabase.query :as query]
            [website.supabase.shape :as shape]
            [website.supabase.realtime :as realtime]))

(defn- params [{:keys [table select filters order-by]}]
  (into {"select" select
         "order" (if (seq order-by)
                   (string/join "," (map (fn [[field direction]] (str (name field) "." (name direction))) order-by))
                   (string/join "," (map #(str (name %) ".asc") (:key (realtime/tables table)))))}
        (map (fn [[field op value]]
               [(name field) (str op "." (case op
                                           "in" (str "(" (string/join "," (map json/write-str value)) ")")
                                           "is" (if (nil? value) "null" value)
                                           value))])) filters))

(defn rows! [{:keys [table select limit offset] :as plan}]
  (loop [offset (or offset 0) remaining limit result []]
    (let [size (if remaining (min 500 remaining) 500)
          rows (concurrent/upstream! #(-> (platform/request! :get table
                        {:query-params (assoc (params plan) "offset" offset "limit" size)}) :body))
          _ (when (config/validation-enabled?)
              (validation/check! (str "Supabase " table) (schema/projected-rows table select) rows))
          ;; The public allowlist applies even if a transport returns extra fields.
          rows (mapv #(select-keys % (map keyword (string/split select #","))) rows)
          result (into result rows)]
      (if (and (= size (count rows)) (or (nil? remaining) (> remaining size)))
        (recur (+ offset size) (when remaining (- remaining size)) result)
        result))))

(defn read! [opts]
  (when-not (query/public-read-query? opts)
    (throw (ex-info "Page plans may read only public collections" {})))
  (let [seed (into {} (map (fn [{:keys [seed-key] :as plan}]
                            [seed-key (rows! plan)]))
                   (query/seed-load-plan opts))
        value (query/query-contract (shape/seed->contract seed) (dissoc opts :offset))]
    (if-let [plan (query/reply-count-plan opts value)]
      (query/with-reply-counts opts value (rows! plan)) value)))

(defn read-many! [queries]
  (let [results
        (apply merge
          (concurrent/map!
           (fn [queries]
             (let [batch (query/batch-query queries)
                   value (read! batch)
                   collection (query/path-part (first (:path-collection batch)))
                   contract {collection (into {} (map (juxt :id :data)) (:docs value))}]
               (into {} (map (fn [opts] [opts (if (= 1 (count queries)) value
                                              (query/query-contract contract opts))])) queries)))
           (vals (group-by query/batch-key (distinct queries)))))]
    (mapv results queries)))

(defn public-context! []
  ;; The trusted-author list is server-owned configuration, never private plan data.
  {:trusted-author-ids
   (if-not (site/enabled? :user)
     []
     (concurrent/upstream!
      #(mapv :user_id (:body (platform/request! :get "auth_roles"
                               {:query-params {"role" "eq.admins", "select" "user_id"}})))))})
