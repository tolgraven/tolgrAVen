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
(defonce *session (atom nil))
(defonce *auth-listener (atom nil))


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
    "eq" (call-method query "eq" (name field) value)
    :eq (call-method query "eq" (name field) value)
    := (call-method query "eq" (name field) value)
    "gt" (call-method query "gt" (name field) value)
    :> (call-method query "gt" (name field) value)
    "gte" (call-method query "gte" (name field) value)
    :>= (call-method query "gte" (name field) value)
    "lt" (call-method query "lt" (name field) value)
    :< (call-method query "lt" (name field) value)
    "lte" (call-method query "lte" (name field) value)
    :<= (call-method query "lte" (name field) value)
    query))

(defn- run-select! [client {:keys [seed-key table filters select]}]
  (letfn [(page! [offset rows]
            (let [q (reduce apply-filter
                            (-> (.from client table) (.select select))
                            filters)
                  ;; Stable ordering is needed across pages; use the first
                  ;; selected column, which is the row identity in these plans.
                  q (.order q (first (.split select ",")))]
              (.then (.range q offset (+ offset 499))
                     (fn [res]
                       (let [{:keys [data error]} (js->clj res :keywordize-keys true)]
                         (when error
                           (throw (ex-info "Supabase select failed"
                                           {:table table :error error})))
                         (if (seq data)
                           (page! (+ offset (count data)) (into rows data))
                           #js {:seedKey (name seed-key) :rows (clj->js rows)}))))))]
    (page! 0 [])))

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
                      (#(seed->result % opts))
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
                      (#(seed->result % opts))
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

(defn ensure-query! [options]
  (let [opts (query/normalize-query options)
        k (query-key opts)]
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

(defn- session-user-id [session]
  (some-> session (gobj/get "user") (gobj/get "id")))

(defn authenticated-request! [method uri data on-success on-error]
  (if-let [client @*client]
    (-> (call-method (gobj/get client "auth") "getSession")
        (.then (fn [result]
                 (if-let [token (some-> result (gobj/get "data") (gobj/get "session") (gobj/get "access_token"))]
                   (let [user-id (session-user-id (some-> result (gobj/get "data") (gobj/get "session")))
                         current? #(= user-id (session-user-id @*session))]
                     ((case method :get ajax/GET :put ajax/PUT :post ajax/POST)
                      uri {:params data
                           :headers {"Authorization" (str "Bearer " token)}
                           :format (ajax/json-request-format)
                           :response-format (ajax/json-response-format {:keywords? true})
                           :handler #(when (current?) (on-success %))
                           :error-handler #(when (current?) (on-error %))}))
                   (on-error {:message "Sign in to continue"}))))
        (.catch #(on-error {:message (.-message %)})))
    (on-error {:message "Supabase is not initialized"})))

(defn sign-in! [method email password on-error]
  (if-let [auth (some-> @*client (gobj/get "auth"))]
    (-> (if (= method :email)
          (call-method auth "signInWithPassword" #js {:email email :password password})
          (call-method auth "signInWithOAuth" #js {:provider (name method)
                                     :options #js {:redirectTo (.-origin js/location)}}))
        (.then #(when-let [error (.-error %)]
                  (on-error {:message (.-message error)})))
        (.catch #(on-error {:message (.-message %)})))
    (on-error {:message "Supabase is not initialized"})))

(defn sign-up! [email password on-success on-error]
  (if-let [auth (some-> @*client (gobj/get "auth"))]
    (-> (call-method auth "signUp" #js {:email email :password password})
        (.then #(if-let [error (.-error %)]
                  (on-error {:message (.-message error)})
                  (on-success (some? (some-> % (gobj/get "data") (gobj/get "session"))))))
        (.catch #(on-error {:message (.-message %)})))
    (on-error {:message "Supabase is not initialized"})))

(defn sign-out! [on-error]
  (when-let [auth (some-> @*client (gobj/get "auth"))]
    (-> (call-method auth "signOut")
        (.then #(when-let [error (.-error %)]
                  (on-error {:message (.-message error)})))
        (.catch #(on-error {:message (.-message %)})))))

(defn init! [settings on-profile on-error]
  (let [{:keys [url anon-key] :as settings'} (normalize-settings settings)]
    (when-not (and (seq url) (seq anon-key))
      (throw (ex-info "Missing Supabase URL or public key" {})))
    (do
      (when-let [listener @*auth-listener] (.unsubscribe listener))
      (when-let [client @*client] (call-method client "removeAllChannels"))
      (reset! *settings settings')
      (reset! *client
              ((.-createClient js/supabase)
               url
               anon-key
               #js {:auth #js {:persistSession true
                               :autoRefreshToken true}}))
      (reset! *auth-listener
              (-> (call-method
                 (gobj/get @*client "auth") "onAuthStateChange"
                 (fn [_ session]
                   (when (not= (session-user-id session) (session-user-id @*session))
                     (on-profile nil))
                   (reset! *session session)
                   ;; Supabase invokes this synchronously under its auth lock.
                   ;; Defer calls back into getSession until that lock is released.
                   (js/setTimeout
                    (fn []
                      (when (identical? session @*session)
                        (if session
                          (authenticated-request!
                           :get "/api/supabase/profile" nil
                           #(when (identical? session @*session) (on-profile %))
                           #(when (identical? session @*session) (on-error %)))
                          (on-profile nil))))
                    0)))
                  (gobj/get "data")
                  (gobj/get "subscription")))
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
