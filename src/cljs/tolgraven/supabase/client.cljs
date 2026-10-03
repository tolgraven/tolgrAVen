(ns tolgraven.supabase.client
  (:require [ajax.core :as ajax]
            [goog.object :as gobj]
            [reagent.ratom :as ratom]
            [taoensso.timbre :as timbre]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.shape :as shape]
            [tolgraven.supabase.realtime :as realtime]))

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

(defn- run-select! [client {:keys [table select filters]}]
  (letfn [(page! [offset rows]
            (let [q (reduce (fn [q [field op value]] (call-method q op (name field) value))
                            (-> (.from client table) (.select select)) filters)
                  q (reduce #(.order %1 (name %2)) q (:key (realtime/tables table)))]
              (.then (.range q offset (+ offset 499))
                     (fn [res]
                       (let [{:keys [data error]} (js->clj res :keywordize-keys true)]
                         (when error (throw (ex-info "Supabase select failed" {:table table})))
                         (let [rows (into rows data)]
                           (if (= 500 (count data)) (page! (+ offset 500) rows) rows)))))))]
    (page! 0 [])))

(defn- result [seed opts]
  (query/query-contract (shape/seed->contract seed) opts))

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
          (-> (js/Promise.all (clj->js (map #(run-select! client %) plan)))
              (.then (fn [rows]
                       (when (current?)
                         (handler (result (into {} (map (fn [p r] [(:seed-key p) r]) plan (array-seq rows))) opts)))))
              (.catch #(when (current?) (error-handler {:message "Unable to read Supabase data"})))))))
    (error-handler {:message "Supabase is not initialized"})))

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
                   (reset! *buffer [])
                   (reset! *loading false))))
        (.catch (fn [_]
                  (when (and (current-entry? entry) (= request @*request))
                    (timbre/error "Supabase snapshot failed" {:table table})
                    (reset! *retry (js/setTimeout #(when (current-entry? entry) (load-table! entry)) 2000))))))))

(defn- ensure-table! [table]
  (when (and @*client (not (get @*tables table))
             (or (not= table "user_documents") (session-user-id @*session)))
    (let [client @*client
          channel (.channel client (str "store-" table))
          entry {:table table :client client :channel channel
                 :*buffer (atom []) :*loading (atom true) :*request (atom 0) :*retry (atom nil)}]
      (swap! *tables assoc table entry)
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
                          (case status
                            "SUBSCRIBED" (load-table! entry)
                            ("CHANNEL_ERROR" "TIMED_OUT")
                            (timbre/warn "Supabase stream disconnected; waiting for rejoin" {:table table})
                            nil))))))))

(defn- remove-table! [table]
  (when-let [{:keys [client channel *retry]} (get @*tables table)]
    (swap! *tables dissoc table)
    (when @*retry (js/clearTimeout @*retry))
    (call-method client "removeChannel" channel)))

(defn ensure-query! [options]
  (let [opts (query/normalize-query options)
        key (pr-str opts)
        tables (query/realtime-tables opts)]
    (when-not (query/direct-read-query? opts)
      (throw (ex-info "Private configuration is server-only" {})))
    (or (get-in @*queries [key :*state])
        (let [state (ratom/make-reaction
                      #(if (and (every? @*loaded tables)
                                (or (not (query/user-document-query? opts)) @*session))
                         (result @*seed opts)
                         (when-not (:path-document opts) {:docs []}))
                      :on-dispose
                      (fn []
                        (swap! *queries dissoc key)
                        (doseq [table tables]
                          (when-not (some #(some #{table} (query/realtime-tables (:opts %))) (vals @*queries))
                            (remove-table! table)
                            (swap! *loaded disj table))))) ]
          (swap! *queries assoc key {:opts opts :*state state})
          (doseq [table tables] (ensure-table! table))
          state))))

(defn authenticated-request! [method uri data on-success on-error]
  (if-let [client @*client]
    (-> (call-method (gobj/get client "auth") "getSession")
        (.then (fn [result]
                 (if-let [token (some-> result (gobj/get "data") (gobj/get "session") (gobj/get "access_token"))]
                   (let [user-id (session-user-id (some-> result (gobj/get "data") (gobj/get "session")))
                         current? #(= user-id (session-user-id @*session))]
                     (when (and (identical? client @*client) (current?))
                       ((case method :get ajax/GET :put ajax/PUT :post ajax/POST)
                      uri {:params (when-not (instance? js/FormData data) data)
                           :body (when (instance? js/FormData data) data)
                           :headers {"Authorization" (str "Bearer " token)}
                           :timeout 60000
                           :format (when-not (instance? js/FormData data) (ajax/json-request-format))
                           :response-format (ajax/json-response-format {:keywords? true})
                           :handler #(when (current?) (on-success %))
                           :error-handler #(when (current?) (on-error %))})))
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
    (doseq [entry (vals @*queries), table (query/realtime-tables (:opts entry))]
      (ensure-table! table))))

(defn write! [path data merge-fields]
  (authenticated-request!
   :post "/api/supabase/documents" {:path path :data data :merge-fields merge-fields}
   (fn [_] nil)
   #(timbre/error "Supabase document write failed" {:message (:message %)})))
