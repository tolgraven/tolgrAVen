(ns tolgraven.supabase.scoped
  "Public blog readers: retain results in app-db, hold live channels only while
   subscribed, and coalesce invalidations before issuing filtered HTTP reads."
  (:require [tolgraven.react :as rf]
            [reagent.ratom :as ratom]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.connection :as connection]
            [tolgraven.service-status :as status]))

(defonce *readers (atom {}))
(defonce *channels (atom {}))
(defonce *tick (atom nil))
(defonce *transport (atom nil))

(defn query-key [opts] (pr-str (query/normalize-query opts)))
(rf/reg-sub :store/scoped (fn [db [_ key]] (get-in db [:store :scoped key])))
(rf/reg-event-db :store/scoped
  (fn [db [_ key value]] (assoc-in db [:store :scoped key] value)))

(declare queue! load! drain!)

(defn- active? [key entry transport]
  (and (identical? entry (get @*readers key))
       (identical? transport @*transport)))

(defn- load! [key {:keys [opts *loading *dirty *retry] :as entry}]
  (when-let [{:keys [read!] :as transport} @*transport]
    (if @*loading (reset! *dirty true)
      (do
        (reset! *loading true)
        (reset! *dirty false)
        (when @*retry (js/clearTimeout @*retry) (reset! *retry nil))
        (read! opts
          (fn [value]
            (when (active? key entry transport)
              (reset! *loading false)
              (rf/dispatch [:store/scoped key value])
              (status/recover! [:supabase-scoped key])
              (when @*dirty (queue!))))
          (fn [_]
            (when (active? key entry transport)
              (reset! *loading false)
              (status/fail! [:supabase-scoped key] "Blog content unavailable"
                            "The requested post or comment thread could not load. Existing content is retained; retrying automatically."
                            #(load! key entry))
              (reset! *retry (js/setTimeout #(when (active? key entry transport) (load! key entry)) 3000)))))))))

(defn- invalidate! [table]
  (doseq [[_ {:keys [opts *dirty]}] @*readers
          :when (some #{table} (query/realtime-tables opts))]
    (reset! *dirty true))
  (queue!))

(defn- close-channel! [table]
  (when-let [{:keys [client channel monitor]} (get @*channels table)]
    (swap! *channels dissoc table)
    (when monitor ((:close! monitor)))
    (.removeChannel ^js client channel)
    (status/recover! [:supabase-scoped-stream table])))

(defn drain! []
  (reset! *tick nil)
  (when-let [{:keys [client] :as transport} @*transport]
    (let [tables (set (mapcat #(query/realtime-tables (:opts %)) (vals @*readers)))]
      (doseq [table (keys @*channels) :when (not (tables table))] (close-channel! table))
      (doseq [table tables :when (not (get @*channels table))]
        (let [^js channel (.channel client (str "blog-scoped-" table))
              current? #(and (identical? transport @*transport)
                             (identical? channel (get-in @*channels [table :channel])))
              monitor (connection/watch!
                       {:id [:supabase-scoped-stream table] :title "Blog live updates unavailable"
                        :current? current? :retry! #(invalidate! table)})]
          (swap! *channels assoc table {:client client :channel channel :monitor monitor})
          ;; One invalidation stream per table; snapshots remain query-scoped.
          ;; DELETE can contain only the PK, so it must invalidate all readers.
          (-> channel
              (.on "postgres_changes" #js {:event "*" :schema "public" :table table}
                   #(when (identical? transport @*transport) (invalidate! table)))
              (.subscribe
               (fn [state]
                 (when (and (identical? transport @*transport)
                            (identical? channel (get-in @*channels [table :channel])))
                   ((:status! monitor) state)
                   (when (= "SUBSCRIBED" state) (invalidate! table))))))))
      ;; HTTP doesn't wait for WebSocket readiness. A SUBSCRIBED invalidation
      ;; repairs the small connection window without discarding live writes.
      (doseq [[key {:keys [*dirty] :as entry}] @*readers :when @*dirty]
        (load! key entry)))))

(defn queue! []
  (when-not @*tick (reset! *tick (js/setTimeout drain! 0))))

(defn connect! [client read!]
  (doseq [table (keys @*channels)] (close-channel! table))
  (reset! *transport (when client {:client client :read! read!}))
  (doseq [[_ {:keys [*loading *dirty *retry]}] @*readers]
    (when @*retry (js/clearTimeout @*retry))
    (reset! *loading false)
    (reset! *dirty true))
  (queue!))

(defn ensure-query! [opts]
  (let [key (query-key opts)]
    (or (get-in @*readers [key :*state])
        (let [state (ratom/make-reaction
                     ;; nil means pending; {:docs []} is a completed empty result.
                     #(deref (rf/subscribe [:store/scoped key]))
                     :on-dispose
                     (fn []
                       (when-let [entry (get @*readers key)]
                         (when @(:*retry entry) (js/clearTimeout @(:*retry entry))))
                       (swap! *readers dissoc key)
                       (status/recover! [:supabase-scoped key])
                       (queue!)))]
          (swap! *readers assoc key {:opts opts :*state state :*loading (atom false)
                                     :*dirty (atom true) :*retry (atom nil)})
          (queue!)
          state))))
