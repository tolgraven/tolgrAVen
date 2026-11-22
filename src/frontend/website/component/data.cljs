(ns website.component.data
  "Shared, mount-independent resource loading. Adapters are registered separately."
  (:require [website.async :as a]
            [website.validation.runtime :as validation]
            [website.schema.declarations :as schemas]
            [website.react :as r]

            [website.render-context :as context]

            [website.service-status :as status]
            [website.component.storage :as storage]
            [website.component.restore :as restore]))

(defonce *sources (atom {}))
(defonce *entries (r/atom {}))
(defonce *installed (atom {}))
(defonce *targets (atom {}))
(def cache-limit 128)
(defonce *queue (atom []))
(defonce *queue-tick (atom nil))
(defn drain!
  "Start the current batch together; nested dependencies join the next tick."
  []
  (when @*queue-tick (js/clearTimeout @*queue-tick))
  (reset! *queue-tick nil)
  (let [batch @*queue]
    (reset! *queue [])
    (doseq [start! batch] (start!))))
(defn enqueue! [start!]
  (swap! *queue conj start!)
  (when-not @*queue-tick (reset! *queue-tick (js/setTimeout drain! 0))))
(defn register-source! [id adapter] (swap! *sources assoc id adapter))
(defn- adapter [resource]
  (doseq [field [:path :into] :when (contains? resource field)]
    (when-not (and (vector? (get resource field)) (seq (get resource field)))
      (throw (ex-info "Data paths must be non-empty vectors" {:field field}))))
  (doseq [field [:timeout-ms :ttl-ms] :when (contains? resource field)]
    (when-not (and (number? (get resource field)) (pos? (get resource field)))
      (throw (ex-info "Data timeouts and TTL must be positive" {:field field}))))
  (or (get @*sources (:source resource))
      (throw (ex-info "Unknown component data source" {:source (:source resource)}))))
(defn resource-key [resource]
  [resource (when-let [scope (:scope (adapter resource))] (scope))])

(r/reg-sub :component-data/installed
  (fn [db query]
    (let [[_ path] (or (:re-frame/query-v query) query)
          missing (js-obj)
          value (get-in db path missing)
          present? (not (identical? missing value))]
      {:present? present?
       :value (when present? value)})))

(r/reg-event-db :component-data/install
  (fn [db [_ path value]] (assoc-in db path value)))
(r/reg-event-db :component-data/remove
  (fn [db [_ path]]
    (if (= 1 (count path)) (dissoc db (first path))
        (update-in db (vec (butlast path)) dissoc (last path)))))

(defn- trim-cache [entries]
  (let [excess (- (count entries) cache-limit)
        victims (->> entries (remove #(= :loading (:status (val %))))
                     (sort-by #(:accessed-at (val %))) (take (max 0 excess)) (map key))]
    (apply dissoc entries victims)))

(defn- ready-value [resource]
  (when-let [read (:read (adapter resource))] (read resource)))

(defn snapshot [resource]
  (let [entry (get @*entries (resource-key resource))
        ready (ready-value resource)]
    (if (:ready? ready) (assoc ready :status :ready) entry)))

(defn- persistence [resource]
  (when-let [options (:persist resource)]
    (merge {:scope (if (#{:strapi :app-db} (:source resource)) :public :user)}
           (when (map? options) options))))
(defn- snapshot-id [resource] [:resource (dissoc resource :persist)])
(defn restore!
  "Install a valid opt-in snapshot synchronously, before the loading branch."
  [resource]
  (let [key (resource-key resource)
        hydrated (when (:into resource) @(r/sub [:component-data/installed (:into resource)]))]
    (when (and (restore/skip-enter?) (:present? hydrated) (nil? (get @*entries key)))
      (swap! *entries assoc key {:resource resource :status :ready :value (:value hydrated)
                                :expires-at (+ (.now js/Date) (or (:ttl-ms resource) 60000))
                                :accessed-at (.now js/Date)}))
    (when (and (nil? (get @*entries key)) (not (:ready? (ready-value resource))))
      (when-let [options (persistence resource)]
        (when-let [{:keys [value expires-at]} (storage/read! (snapshot-id resource) options)]
          (try
          (when-let [restore! (:restore! (adapter resource))] (restore! resource value))
          (when-let [path (:into resource)]
            (r/dispatch-sync [:component-data/install path value])
            (swap! *installed assoc path {:resource resource :value value}))
          (swap! *entries assoc key {:resource resource :status :ready :value value
                                    :expires-at expires-at :accessed-at (.now js/Date)})
          true
          (catch :default _ (storage/remove! (snapshot-id resource) options) nil)))))))

(declare ensure! retry!)

(defn invalidate!
  "Drop matching resources and reject their waiters. Late results cannot install.
   Used by auth changes as well as explicit refreshes."
  [matches?]
  (doseq [[key {:keys [resource reject!]}] @*entries :when (matches? resource)]
    (swap! *entries dissoc key)
    (status/recover! [:component-data key])
    (when reject! (reject! (js/Error. "Component data request invalidated"))))
  (doseq [[path {:keys [resource value]}] @*installed :when (matches? resource)]
    (swap! *installed dissoc path)
    ;; Do not remove a value another producer has since replaced.
    (when (= value (storage/state-value path))
      (r/dispatch-sync [:component-data/remove path]))))

(def default-timeout-ms 15000)

(defn- current-load? [key token]
  (identical? token (get-in @*entries [key :token])))

(defn- check-load! [key token]
  (when-not (current-load? key token)
    (throw (js/Error. "Component data request invalidated"))))

(defn- complete-load! [resource key token value]
  (check-load! key token)
  (when-let [path (:into resource)]
    ;; A newer request targeting this path may belong to a different resource.
    (when (identical? token (get @*targets path))
      (r/dispatch-sync [:component-data/install path value])
      (swap! *installed assoc path {:resource resource :value value})))
  (swap! *entries update key merge
         {:status :ready :value value :installed? (boolean (:into resource))
          :expires-at (+ (.now js/Date) (or (:ttl-ms resource) 60000)) :reject! nil})
  (when-let [options (persistence resource)]
    (let [id (snapshot-id resource)
          read-current #(if-let [path (:into resource)]
                          (storage/state-value path)
                          (get-in @*entries [key :value] storage/missing))]
      (storage/write! id value options)
      (storage/track! id read-current options)))
  (status/recover! [:component-data key])
  value)

(defn- fail-load! [resource key token error]
  (when (current-load? key token)
    (swap! *entries update key merge {:status :error :error error :reject! nil})
    (status/fail! [:component-data key] "Component data unavailable"
                  "Some content could not be loaded. Retry to load it again."
                  #(a/catch-error (retry! [resource]) (constantly nil))))
  (throw error))

(defn- ^:async read-resource! [resource key token load!]
  (let [options (persistence resource)]
    (await (when options (storage/ready! options)))
    (check-load! key token)
    (if-let [saved (when options (storage/read! (snapshot-id resource) options))]
      (try
        (when-let [restore! (:restore! (adapter resource))] (restore! resource (:value saved)))
        (:value saved)
        (catch :default _
          (storage/remove! (snapshot-id resource) options)
          (load! resource)))
      (load! resource))))

(defn- begin-load! [resource key {:keys [load! timeout-ms]} now]
  (let [token (js-obj)
        {:keys [promise resolve! reject!]} (a/deferred)
        timer (js/setTimeout #(reject! (js/Error. "Component data request timed out"))
                             (or (:timeout-ms resource) timeout-ms default-timeout-ms))
        cleanup! (fn []
                   (js/clearTimeout timer)
                   (when-let [path (:into resource)]
                     (when (identical? token (get @*targets path)) (swap! *targets dissoc path))))
        finish (-> promise
                   (a/then #(complete-load! resource key token %))
                   (a/catch-error #(fail-load! resource key token %))
                   (a/finally-do cleanup!))
        entry {:resource resource :token token :status :loading
               :promise finish :reject! reject! :accessed-at now}]
    (when-let [path (:into resource)] (swap! *targets assoc path token))
    (swap! *entries #(trim-cache (assoc % key entry)))
    (enqueue! #(when (current-load? key token)
                 (a/then (read-resource! resource key token load!) resolve! reject!)))
    finish))

(defn ensure!
  "Queue immediately, independent of React. Concurrent callers share one load.
   Errors are retained until retry; successful remote snapshots default to 60s TTL."
  [resource]
  (try
    (validation/check! "data dependency" schemas/dependency resource)
    (restore! resource)
    (let [source (adapter resource)
          key (resource-key resource)
          ready (ready-value resource)
          existing (get @*entries key)
          now (.now js/Date)]
      (cond
        (:ready? ready)
        (do (when (= :error (:status existing))
              (swap! *entries dissoc key)
              (status/recover! [:component-data key]))
            (a/resolved (:value ready)))
        (= :loading (:status existing)) (:promise existing)
        (= :error (:status existing)) (a/rejected (:error existing))
        (and (nil? (:read source)) (= :ready (:status existing)) (< now (:expires-at existing)))
        (a/resolved (:value existing))
        :else (begin-load! resource key source now)))
    (catch :default error (a/rejected error))))

(defn ensure-all! [resources]
  (a/all (mapv ensure! resources)))
(defn prefetch! [resources]
  (-> (ensure-all! resources) (a/catch-error (fn [_] nil))))
(defn retry! [resources]
  (let [requested (set resources)]
    (doseq [resource resources :let [options (persistence resource)] :when options]
      (storage/remove! (snapshot-id resource) options))
    (invalidate! requested)
    (ensure-all! resources)))

(defn state [resources]
  (let [states (mapv snapshot resources)]
    (cond
      (some #(= :error (:status %)) states) :error
      (every? #(= :ready (:status %)) states) :ready
      :else :loading)))

(r/reg-sub-raw :component-data/status
  {:args [:cat [:vector schemas/dependency]]
   :result [:enum :loading :ready :error]}
  (fn [_ query]
    (let [[_ resources] (or (:re-frame/query-v query) query)]
      ;; Acquisition belongs to the managed source lifecycle, just as for module
      ;; and CMS subscriptions. Preloads use this same deduplicated queue. The
      ;; derived reaction only reads status; SSR must use supplied snapshots.
      ;; Acquisition reads caches synchronously without subscribing a speculative
      ;; render owner to unrelated resources restored into those caches later.
      (when-not context/*server?* (r/without-tracking #(prefetch! resources)))
      (r/make-reaction #(state resources)))))

(defn wait-for!
  "Observe a ratom without creating a React subscription. Always remove the watch."
  [reference read! timeout-ms]
  (a/create
   (fn [resolve reject]
     (let [key (random-uuid) *timer (atom nil)
           cleanup! #(do (remove-watch reference key) (when @*timer (js/clearTimeout @*timer)))
           check! (fn []
                    (try
                      (let [{:keys [ready? value]} (read!)]
                        (when ready? (cleanup!) (resolve value)))
                      (catch :default error (cleanup!) (reject error))))]
       (add-watch reference key (fn [& _] (check!)))
       (reset! *timer (js/setTimeout #(do (cleanup!) (reject (js/Error. "Data dependency did not become ready")))
                                    timeout-ms))
       (check!)))))

(r/reg-fx :component-data/load prefetch!)
(r/reg-event-fx :component-data/load
  (fn [_ [_ resources]] {:component-data/load resources}))

(defn failure [resources]
  (some (fn [resource]
          (let [result (snapshot resource)]
            (when (= :error (:status result)) (:error result))))
        resources))

(defn retry-background! [resources]
  ;; The shared loader records failures and reports them through the HUD.
  (-> (retry! resources) (a/catch-error (fn [_] nil))))
