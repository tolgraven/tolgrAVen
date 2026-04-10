(ns tolgraven.supabase.client
  (:require
   [ajax.core :as ajax]
   [goog.object :as gobj]
   [reagent.ratom :as ratom]
   [taoensso.timbre :as timbre]
   [tolgraven.supabase.query :as query]
   [tolgraven.supabase.shape :as shape]))

(defonce *client (atom nil))
(defonce *settings (atom nil))
(defonce *queries (atom {}))

(def ^:private empty-seed
  {:roles []
   :users []
   :blog_posts []
   :blog_comments []
   :chat_messages []
   :service_configs []})

(defn- query-key [opts]
  (pr-str opts))

(defn- channel-key [opts]
  (str "store-" (hash (query-key opts))))

(defn- default-state [opts]
  (if (:path-document opts)
    nil
    {:docs []}))

(defn- initialized? []
  (some? @*client))

(defn- normalize-settings [{:keys [url anon-key anonKey]}]
  {:url url
   :anon-key (or anon-key anonKey)})

(defn- js-error->map [error]
  (cond
    (map? error) error
    (nil? error) nil
    :else (js->clj error :keywordize-keys true)))

(defn- fallback-refresh-entry! [{:keys [opts *state]}]
  (ajax/POST
   "/api/supabase/store/query"
   {:params opts
    :format (ajax/json-request-format)
    :response-format (ajax/json-response-format {:keywords? true})
    :handler #(reset! *state %)
    :error-handler (fn [error]
                     (timbre/error "Supabase fallback query failed"
                                   {:opts opts :error error}))}))

(defn- call-method
  ([target method]
   (.call (gobj/get target method) target))
  ([target method arg1]
   (.call (gobj/get target method) target arg1))
  ([target method arg1 arg2]
   (.call (gobj/get target method) target arg1 arg2)))

(defn- apply-filter [query [field op value]]
  (case op
    "eq" (call-method query "eq" field value)
    :eq (call-method query "eq" field value)
    := (call-method query "eq" field value)
    "gt" (call-method query "gt" field value)
    :> (call-method query "gt" field value)
    "gte" (call-method query "gte" field value)
    :>= (call-method query "gte" field value)
    "lt" (call-method query "lt" field value)
    :< (call-method query "lt" field value)
    "lte" (call-method query "lte" field value)
    :<= (call-method query "lte" field value)
    query))

(defn- run-select! [client {:keys [seed-key table filters]}]
  (let [query (reduce apply-filter
                      (-> (.from client table)
                          (.select "*"))
                      filters)]
    (.then query
           (fn [res]
             (let [{:keys [data error]} (js->clj res :keywordize-keys true)]
               (when error
                 (throw (ex-info "Supabase select failed"
                                 {:table table
                                  :filters filters
                                  :error error})))
               #js {:seedKey (name seed-key)
                    :rows (clj->js (vec data))})))))

(defn- fetch-seed! [client opts]
  (let [plan (query/seed-load-plan opts)]
    (if-not (seq plan)
      (js/Promise.resolve (clj->js empty-seed))
      (-> (js/Promise.all (clj->js (map #(run-select! client %) plan)))
          (.then
           (fn [results]
             (clj->js
              (reduce
               (fn [seed result]
                 (let [{:keys [seedKey rows]} (js->clj result :keywordize-keys true)]
                   (assoc seed (keyword seedKey) (vec rows))))
               empty-seed
               (array-seq results)))))))))

(defn- seed->result [seed opts]
  (-> seed
      (shape/seed->contract)
      (query/query-contract opts)))

(declare refresh-entry!)

(defn- resubscribe-entry! [{:keys [opts channel] :as entry}]
  (when-let [client @*client]
    (when channel
      (call-method client "removeChannel" channel))
    (if (query/direct-read-query? opts)
      (let [channel-name (channel-key opts)
            callback (fn [_] (refresh-entry! entry))
            channel' (reduce
                      (fn [ch table]
                        (.on ch
                             "postgres_changes"
                             #js {:event "*"
                                  :schema "public"
                                  :table table}
                             callback))
                      (.channel client channel-name)
                      (query/realtime-tables opts))]
        (.subscribe channel')
        (assoc entry :channel channel'))
      (assoc entry :channel nil))))

(defn refresh-entry! [{:keys [opts *state] :as entry}]
  (if (and (initialized?)
           (query/direct-read-query? opts))
    (-> (fetch-seed! @*client opts)
        (.then (fn [seed]
                 (->> (js->clj seed :keywordize-keys true)
                      (seed->result opts)
                      (reset! *state))))
        (.catch (fn [error]
                  (timbre/error "Supabase direct query failed"
                                {:opts opts
                                 :error (js-error->map error)}))))
    (fallback-refresh-entry! entry)))

(defn read-once! [opts handler error-handler]
  (if (and (initialized?)
           (query/direct-read-query? opts))
    (-> (fetch-seed! @*client opts)
        (.then (fn [seed]
                 (->> (js->clj seed :keywordize-keys true)
                      (seed->result opts)
                      (handler))))
        (.catch (fn [error]
                  (let [error' (js-error->map error)]
                    (timbre/error "Supabase direct read failed"
                                  {:opts opts
                                   :error error'})
                    (when error-handler
                      (error-handler error'))))))
    (ajax/POST
     "/api/supabase/store/query"
     {:params opts
      :format (ajax/json-request-format)
      :response-format (ajax/json-response-format {:keywords? true})
      :handler handler
      :error-handler error-handler})))

(defn ensure-query! [opts]
  (let [k (query-key opts)]
    (or (get-in @*queries [k :*state])
        (let [entry {:opts opts
                     :*state (ratom/atom (default-state opts))
                     :channel nil}
              entry' (cond-> entry
                       (initialized?) resubscribe-entry!)]
          (swap! *queries assoc k entry')
          (refresh-entry! entry')
          (:*state entry')))))

(defn refresh-all! []
  (doseq [[k entry] @*queries]
    (refresh-entry! (or entry (get @*queries k)))))

(defn init! [settings]
  (let [{:keys [url anon-key] :as settings'} (normalize-settings settings)]
    (when (and url anon-key)
      (reset! *settings settings')
      (reset! *client
              ((.-createClient js/supabase)
               url
               anon-key
               #js {:auth #js {:persistSession false
                               :autoRefreshToken false}}))
      (swap! *queries
             (fn [entries]
               (into {}
                     (map (fn [[k entry]]
                            [k (resubscribe-entry! entry)]))
                     entries)))
      (refresh-all!))))

(defn write! [path data merge-fields]
  (ajax/POST
   "/api/supabase/store/write"
   {:params {:path path
             :data data
             :merge-fields merge-fields}
    :format (ajax/json-request-format)
    :response-format (ajax/json-response-format {:keywords? true})
    :handler (fn [_] (refresh-all!))
    :error-handler (fn [error]
                     (timbre/error "Supabase write failed" {:path path :error error}))}))
