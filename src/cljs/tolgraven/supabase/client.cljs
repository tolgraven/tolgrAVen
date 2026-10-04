(ns tolgraven.supabase.client
  (:require [ajax.core :as ajax]
            [tolgraven.react :as rf]
            [re-frame.db :as rfdb]
            [tolgraven.service-status :as status]
            [goog.object :as gobj]
            [reagent.ratom :as ratom]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.shape :as shape]
            [tolgraven.supabase.realtime :as realtime]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.supabase.connection :as connection]))

(defonce *client (atom nil))
(defonce *settings (atom nil))
(defonce *queries (atom {}))
(defonce *tables (atom {}))
(defonce *seed (ratom/atom realtime/empty-seed))
(defonce *loaded (ratom/atom #{}))
(defonce *session (ratom/atom nil))
(defonce *auth-listener (atom nil))

(defn- call-method
  ([target method] (.call (gobj/get target method) target))
  ([target method arg1] (.call (gobj/get target method) target arg1))
  ([target method arg1 arg2] (.call (gobj/get target method) target arg1 arg2)))

(defn- session-user-id [session]
  (some-> session (gobj/get "user") (gobj/get "id")))

(defn- session-owner-id [session]
  (or (some-> session (gobj/get "user") (gobj/get "app_metadata") (gobj/get "site_user_id"))
      (session-user-id session)))

(defn- session-key [session]
  [(session-user-id session) (session-owner-id session)])

(defn- run-select! [client {:keys [table select filters limit order-by] :as plan}]
  (letfn [(page! [offset rows]
            (let [q (reduce (fn [q [field op value]]
                              ;; The SDK's .match method means equality on a map;
                              ;; PostgREST's regex operator belongs in .filter.
                              (if (= op "match")
                                (.filter q (name field) op value)
                                (call-method q op (name field)
                                             (if (= op "in") (into-array value) value))))
                            (-> (.from client table) (.select select)) filters)
                  q (if (seq order-by)
                      (reduce (fn [q [field direction]] (.order q (name field) #js {:ascending (= :asc direction)})) q order-by)
                      (reduce #(.order %1 (name %2)) q (:key (realtime/tables table))))
                  size (if limit (min 500 (- limit offset)) 500)
                  start (+ (or (:offset plan) 0) offset)]
              (.then (status/within! (.range q start (+ start (dec size))) 15000)
                     (fn [res]
                       (let [{:keys [data error]} (js->clj res :keywordize-keys true)]
                         (when error (throw (ex-info "Supabase select failed" {:table table})))
                         (let [rows (into rows data)]
                           (if (and (= size (count data)) (or (nil? limit) (< (+ offset size) limit)))
                             (page! (+ offset size) rows) rows)))))))]
    (-> (js/Promise.resolve) (.then (fn [] (page! 0 []))))))

(defn- result [seed opts]
  (query/query-contract (shape/seed->contract seed) opts))

(defn- with-reply-counts! [client opts value]
  (if-let [plan (query/reply-count-plan opts value)]
    (-> (run-select! client plan)
        (.then #(query/with-reply-counts opts value %)))
    (js/Promise.resolve value)))

(defn read-once! [opts handler error-handler]
  (if-let [client @*client]
    (let [private? (query/user-document-query? opts)
          owner (session-key @*session)
          current? #(and (identical? client @*client)
                         (or (not private?) (= owner (session-key @*session))))]
      (cond
        (not (query/direct-read-query? opts))
        (error-handler {:message "Use the server integration endpoint for private configuration"})

        (and private? (not (session-user-id @*session)))
        (error-handler {:message "Sign in to read private documents"})

        :else
        (let [plan (query/seed-load-plan opts)]
          (-> (js/Promise.all (into-array (map #(run-select! client %) plan)))
              (.then (fn [rows]
                       ;; The transport already applied the page offset.
                       (let [value (result (into {} (map (fn [p r] [(:seed-key p) r]) plan (array-seq rows))) (dissoc opts :offset))]
                         (if (:reply-counts? opts) (with-reply-counts! client opts value) value))))
              (.then (fn [value]
                       (when (current?)
                         (status/recover! :supabase-read)
                         (handler value))))
              (.catch (fn [_]
                        (when (current?)
                          (status/fail! :supabase-read "Supabase content unavailable"
                                        "A content request failed. Reload the page to retry."
                                        #(.reload js/location))
                          (error-handler {:message "Unable to read Supabase data"}))))))))
    (error-handler {:message "Supabase is not initialized"})))

(defonce *preloads (atom {}))
(defonce *client-generation (atom 0))
(add-watch *client ::generation
           (fn [_ _ old new] (when-not (identical? old new) (swap! *client-generation inc))))
(defn query-key [opts] (pr-str (query/normalize-query opts)))
(rf/reg-event-db :store/cache-query
  (fn [db [_ key value]] (assoc-in db [:store :query-cache key] value)))
(rf/reg-event-db :store/cache-seed
  (fn [db [_ snapshot]]
    (cond-> (assoc-in db [:store :snapshot] snapshot)
      (contains? (:loaded snapshot) "site_users")
      (assoc-in [:store :public "users"] (into {} (map (fn [[id user]] [id (select-keys user [:id :name :avatar :bg-color :comment-count :karma :seq-id])]))
                      (get (shape/seed->contract (:seed snapshot)) "users"))))))
(defn- publish-cache! [& _]
  (rf/dispatch [:store/cache-seed {:seed @*seed :loaded @*loaded
                                  :owner (session-key @*session) :generation @*client-generation}]))
(add-watch *seed ::app-db-cache publish-cache!)
(add-watch *loaded ::app-db-cache publish-cache!)
(add-watch *session ::app-db-cache publish-cache!)

(rf/reg-sub :store/query-value
  (fn [db [_ opts owner generation signed-in?]]
    (let [{:keys [seed loaded] :as snapshot} (get-in db [:store :snapshot])
          tables (query/realtime-tables opts)
          cached (get-in db [:store :query-cache (query-key opts)])
          private? (query/user-document-query? opts)]
      (cond
        (and (seq tables) (every? (or loaded #{}) tables)
             (= generation (:generation snapshot))
             (or (not private?) (and signed-in? (= owner (:owner snapshot)))))
        (result seed opts)

        ;; Readers refresh on acquisition. Keep cached content visible while
        ;; waiting; expiry governs preloading, not the pure subscription.
        (and (= owner (:owner cached)) (= generation (:generation cached)))
        (:value cached)

        (not private?)
        (query/query-contract (get-in db [:store :public]) opts)

        :else nil))))

(defn cached-query [opts]
  (let [opts (query/normalize-query opts)
        tables (query/realtime-tables opts)
        entry (get-in @rfdb/app-db [:store :query-cache (query-key opts)])]
    (cond
      (and (query/scoped-query? opts) (get-in @rfdb/app-db [:store :scoped (query-key opts)]))
      {:ready? true :value (get-in @rfdb/app-db [:store :scoped (query-key opts)])}
      (and (seq tables) (every? @*loaded tables)
           (or (not (query/user-document-query? opts)) @*session))
      {:ready? true :value (result @*seed opts)}
      (and (= (:owner entry) (session-key @*session))
           (= (:generation entry) @*client-generation)
           (> (:expires-at entry 0) (.now js/Date)))
      {:ready? true :value (:value entry)}
      :else {:ready? false})))

(defn preload-query!
  "Load without holding a realtime subscription. Identical queued reads share a
   Promise and populate the same app-db cache used on a later subscription."
  [opts]
  (let [key (query-key opts) owner (session-key @*session) client @*client
        pending-key [key owner client]
        cached (cached-query opts)]
    (if (:ready? cached) (js/Promise.resolve (:value cached))
      (or (get @*preloads pending-key)
          (let [promise (js/Promise.
                         (fn [resolve reject]
                           (js/setTimeout
                            #(if (and (identical? client @*client) (= owner (session-key @*session)))
                               (read-once! opts
                                 (fn [value]
                                   (when (and (identical? client @*client) (= owner (session-key @*session)))
                                     (when (query/scoped-query? opts)
                                       (rf/dispatch-sync [:store/scoped key value]))
                                     (rf/dispatch-sync [:store/cache-query key
                                                        {:value value :owner owner :generation @*client-generation
                                                         :expires-at (+ (.now js/Date) 60000)}]))
                                   (resolve value))
                                 (fn [_] (reject (js/Error. "Supabase dependency unavailable"))))
                               (reject (js/Error. "Supabase session changed"))) 0)))
                promise (-> (status/within! promise 15000)
                            (.finally #(swap! *preloads dissoc pending-key)))]
            (swap! *preloads assoc pending-key promise)
            promise)))))

(declare ensure-table! load-table!)

(defn- current-entry? [{:keys [table client] :as entry}]
  (and (identical? client @*client) (identical? entry (get @*tables table))))

(defn- load-table! [{:keys [table client *buffer *loading *request *retry] :as entry}]
  (let [request (swap! *request inc)
        owner (session-key @*session)]
    (when @*retry (js/clearTimeout @*retry) (reset! *retry nil))
    (reset! *buffer [])
    (reset! *loading true)
    (-> (run-select! client {:table table :select (query/public-columns table)})
        (.then (fn [rows]
                 (when (and (current-entry? entry) (= request @*request)
                            (or (not= table "user_documents") (= owner (session-key @*session))))
                   ;; Subscribe before reading. Events during paginated loading are
                   ;; replayed over the snapshot so it cannot discard live writes.
                   (swap! *seed realtime/install-snapshot table rows @*buffer)
                   (swap! *loaded conj table)
                   (status/recover! [:supabase-load table])
                   (reset! *buffer [])
                   (reset! *loading false))))
        (.catch (fn [_]
                  (when (and (current-entry? entry) (= request @*request))
                    (status/fail! [:supabase-load table] "Supabase content unavailable"
                                  (str "Could not load " (get {"site_users" "profiles" "blog_posts" "blog posts"
                                                            "blog_comments" "comments" "chat_messages" "chat messages"
                                                            "user_documents" "your documents"} table "content")
                                       ". Retrying automatically; existing content is retained.")
                                  #(when (current-entry? entry) (load-table! entry)))
                    (reset! *retry (js/setTimeout #(when (current-entry? entry) (load-table! entry)) 2000))))))))

(defn- ensure-table! [table]
  (when (and @*client (not (get @*tables table))
             (or (not= table "user_documents") (session-user-id @*session)))
    (let [client @*client
          ^js channel (.channel client (str "store-" table))
          entry {:table table :client client :channel channel
                 :*buffer (atom []) :*loading (atom true) :*request (atom 0) :*retry (atom nil)
                 :*connection (atom nil)}]
      (swap! *tables assoc table entry)
      (reset! (:*connection entry)
              (connection/watch!
               {:id [:supabase-stream table] :title "Supabase live updates unavailable"
                :current? #(current-entry? entry)
                :retry! #(when (current-entry? entry) (load-table! entry))
                :failed! #(when (current-entry? entry) (load-table! entry))}))
      (-> channel
          (.on "postgres_changes" (clj->js (cond-> {:event "*" :schema "public" :table table}
                                                   (= table "user_documents")
                                                   (assoc :filter (str "owner_id=eq." (session-owner-id @*session)))))
               (fn [payload]
                 (when (current-entry? entry)
                   (let [change (js->clj payload :keywordize-keys true)
                         row (if (= "DELETE" (:eventType change)) (:old change) (:new change))]
                     (when (or (not= table "user_documents")
                               (= (:owner_id row) (session-owner-id @*session)))
                       (when @(:*loading entry) (swap! (:*buffer entry) conj change))
                       (swap! *seed realtime/apply-change change))))))
          (.subscribe (fn [status _]
                        (when (current-entry? entry)
                          ((:status! @(:*connection entry)) status)
                          (when (#{"SUBSCRIBED" "CHANNEL_ERROR" "CLOSED"} status)
                            ;; HTTP remains useful during a silent initial retry.
                            (load-table! entry)))))))))

(defn- remove-table! [table]
  (when-let [{:keys [client channel *retry *connection]} (get @*tables table)]
    (swap! *tables dissoc table)
    (status/recover! [:supabase-load table])
    (status/recover! [:supabase-stream table])
    (when @*retry (js/clearTimeout @*retry))
    (when (and *connection @*connection) ((:close! @*connection)))
    (call-method client "removeChannel" channel)))

(defonce *query-tick (atom nil))
(defn drain-queries! []
  (when @*query-tick (js/clearTimeout @*query-tick))
  (reset! *query-tick nil)
  (doseq [table (set (mapcat #(query/realtime-tables (:opts %)) (vals @*queries)))]
    (ensure-table! table)))
(defn queue-queries! []
  (when-not @*query-tick (reset! *query-tick (js/setTimeout drain-queries! 0))))

(defn ensure-query! [options]
  (if (query/scoped-query? options)
    (scoped/ensure-query! options)
    (let [opts (query/normalize-query options)
        key (pr-str opts)
        tables (query/realtime-tables opts)]
    (when-not (query/direct-read-query? opts)
      (throw (ex-info "Private configuration is server-only" {})))
    (or (get-in @*queries [key :*state])
        (let [state (ratom/make-reaction
                      ;; Transport atoms coordinate requests; all content delivered
                      ;; to consumers comes from event-populated app-db subscriptions.
                      #(deref (rf/subscribe [:store/query-value opts (session-key @*session)
                                            @*client-generation (some? @*session)]))
                      :on-dispose
                      (fn []
                        (swap! *queries dissoc key)
                        (doseq [table tables]
                          (when-not (some #(some #{table} (query/realtime-tables (:opts %))) (vals @*queries))
                            (remove-table! table))))) ]
          (swap! *queries assoc key {:opts opts :*state state})
          ;; A component can disappear before the next tick; do not create
          ;; channels for readers that no longer exist.
          (queue-queries!)
          state)))))

(defn authenticated-request! [method uri data on-success on-error]
  (if-let [client @*client]
    (let [owner (session-key @*session)
          current? #(and (identical? client @*client)
                         (= owner (session-key @*session)))
          fail! #(when (current?) (on-error %))]
      (-> (status/within! (call-method (gobj/get client "auth") "getSession") 15000)
          (.then (fn [result]
                   (when (gobj/get result "error") (throw (js/Error. "Unable to restore your Supabase session")))
                   (let [session (some-> result (gobj/get "data") (gobj/get "session"))
                         token (some-> session (gobj/get "access_token"))]
                     (when (and (current?) (= owner (session-key session)))
                       (if token
                         ((case method :get ajax/GET :put ajax/PUT :post ajax/POST)
                          uri {:params (when-not (instance? js/FormData data) data)
                               :body (when (instance? js/FormData data) data)
                               :headers {"Authorization" (str "Bearer " token)}
                               :timeout 60000
                               :format (when-not (instance? js/FormData data) (ajax/json-request-format))
                               :response-format (ajax/json-response-format {:keywords? true})
                               :handler #(when (current?) (on-success %))
                               :error-handler fail!})
                         (fail! {:message "Sign in to continue"}))))))
          (.catch #(fail! {:message (.-message %)}))))
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

(defn change-password! [current-password new-password on-success on-error]
  (if-let [auth (some-> @*client (gobj/get "auth"))]
    (let [session @*session
          user-id (session-user-id session)
          email (some-> session (gobj/get "user") (gobj/get "email"))
          check! (fn [result]
                   (when-let [error (.-error result)]
                     (throw (js/Error. (.-message error))))
                   result)]
      (if (and user-id (string? new-password) (<= 6 (count new-password)))
        (-> (if (seq current-password)
              (call-method auth "signInWithPassword" #js {:email email :password current-password})
              (js/Promise.resolve #js {}))
            (.then (fn [result]
                     (check! result)
                     (when-not (= user-id (session-user-id @*session))
                       (throw (js/Error. "Your account changed; please try again")))
                     (call-method auth "updateUser" #js {:password new-password})))
            (.then (fn [result] (check! result) (on-success)))
            (.catch #(on-error {:message (.-message %)})))
        (on-error {:message "Sign in and choose a password with at least six characters"})))
    (on-error {:message "Supabase is not initialized"})))

(defn sign-out! [on-error]
  (when-let [auth (some-> @*client (gobj/get "auth"))]
    (-> (call-method auth "signOut")
        (.then #(when-let [error (.-error %)]
                  (on-error {:message (.-message error)})))
        (.catch #(on-error {:message (.-message %)})))))

(add-watch *client ::scoped
  (fn [_ _ old client]
    (when-not (identical? old client) (scoped/connect! client read-once!))))

(defn init! [{:keys [url anon-key anonKey] :as settings} on-profile on-error]
  (let [anon-key (or anon-key anonKey)]
    (when-not (and (seq url) (seq anon-key))
      (throw (ex-info "Missing Supabase URL or public key" {})))
    (when-let [listener @*auth-listener] (.unsubscribe listener))
    (doseq [table (keys @*tables)] (remove-table! table))
    (reset! *session nil)
    (reset! *seed realtime/empty-seed)
    (reset! *loaded #{})
    (reset! *settings settings)
    (reset! *client ((.-createClient js/supabase) url anon-key
                    #js {:auth #js {:persistSession true :autoRefreshToken true}}))
    (reset! *auth-listener
            (-> (call-method (gobj/get @*client "auth") "onAuthStateChange"
                  (fn [_ session]
                    (status/recover! :supabase-session)
                    (when (not= (session-key session) (session-key @*session))
                      (on-profile nil)
                      (remove-table! "user_documents")
                      (swap! *seed assoc :store_documents [])
                      (swap! *loaded disj "user_documents"))
                    (reset! *session session)
                    ;; Auth calls this under its lock; defer getSession calls.
                    (js/setTimeout
                     (fn []
                       (when (identical? session @*session)
                         (doseq [entry (vals @*queries), table (query/realtime-tables (:opts entry))]
                           (ensure-table! table))
                         (if session
                           (authenticated-request! :get "/api/supabase/profile" nil
                             #(when (identical? session @*session) (on-profile %))
                             #(when (identical? session @*session) (on-error %)))
                           (on-profile nil)))) 0)))
                (gobj/get "data") (gobj/get "subscription")))
    (let [client @*client]
      (-> (status/within! (call-method (gobj/get client "auth") "getSession") 15000)
          (.then (fn [result]
                   (when (gobj/get result "error") (throw (js/Error. "Session initialization failed")))
                   (when (identical? client @*client) (status/recover! :supabase-session))))
          (.catch (fn [_]
                    (when (identical? client @*client)
                      (status/fail! :supabase-session "Supabase session unavailable"
                                    "Your account session could not be restored. Reload to retry."
                                    #(.reload js/location)))))))
    (doseq [entry (vals @*queries), table (query/realtime-tables (:opts entry))]
      (ensure-table! table))))

(defn write! [path data merge-fields]
  (authenticated-request!
   :post "/api/supabase/documents" {:path path :data data :merge-fields merge-fields}
   (fn [_] (status/recover! :supabase-write))
   #(status/fail! :supabase-write "Supabase save failed"
                  "Your changes could not be saved. Please try saving again." nil)))
