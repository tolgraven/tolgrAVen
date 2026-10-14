(ns website.component.storage
  "Opt-in snapshots, one disk envelope per owner. Components only touch memory."
  (:require
    [cljs.reader :as reader]
    [clojure.string :as string]
    [website.component.storage-disk :as disk]
    [website.validation :as validation]
    [website.ssr.schema :as schema]
    [website.react :as r]
    [website.service-status :as status]))

(def missing (js-obj))
(r/reg-sub :component-storage/value
  (fn [db query]
    (let [[_ path] (or (:re-frame/query-v query) query)]
      (get-in db path missing))))
(defn state-value [path] @(r/sub [:component-storage/value path]))
(def prefix "tolgraven.component.v2:")
(defonce *public-saved? (atom false))
(defonce *identity (r/atom nil))
(defonce *tracked (atom {}))
(defonce *pending (atom nil))
(defonce *buckets (atom {}))
(defonce *reads (atom {}))
(defonce *dirty (atom #{}))
;; Consumed snapshots remain readable in memory, but are excluded from disk.
(defonce *consumed (atom {}))
(defonce *ready (atom #{}))
(defonce *read-errors (atom #{}))
(defonce *heavy-ready (atom #{}))
(defonce *heavy-read-errors (atom #{}))
(defonce *deleted-paths (atom {}))
(defonce *heavy-owners (atom #{}))
(defonce *migrating (atom #{}))
(defonce *legacy-text (atom {}))
(defonce *legacy-backed (atom #{}))
(defonce *disk-writes (atom {}))
(defonce *disk-generations (atom {}))

(defn heavy-id? [id]
  (or (= :public-content id)
      (and (vector? id)
           (or (= :resource (first id))
               (and (= :state (first id)) (#{:store :content} (first (second id))))))))
(defn- prefix? [parent child]
  (and (<= (count parent) (count child)) (= (vec parent) (subvec (vec child) 0 (count parent)))))
(defn- snapshot-paths [id]
  (cond
    (= id :public-content) [[:content]]
    (and (vector? id) (= :state (first id))) [(second id)]
    (and (vector? id) (= :resource (first id)))
    (let [{:keys [source path into keys]} (second id)]
      (concat (when into [into])
              (case source :app-db [path] :strapi (map #(vector :content %) keys) [])))
    :else []))
(defn- affected? [id removed]
  (some (fn [path] (some #(or (prefix? path %) (prefix? % path)) removed)) (snapshot-paths id)))
(defn read-disk! [key]
  (when (exists? js/window) (.getItem (.-localStorage js/window) key)))
(defn write-disk! [key value]
  (when (exists? js/window) (.setItem (.-localStorage js/window) key value)))
(defn remove-disk! [key]
  (when (exists? js/window) (.removeItem (.-localStorage js/window) key)))
(defn migration-key [account] (str prefix (pr-str account) ":heavy-migration"))

(defn- read-local-owner! [account disk-key]
  (let [text (read-disk! disk-key)
        legacy (read-disk! (migration-key account))]
    (-> (js/Promise.all #js [(disk/read-local! text)
                            (.catch (disk/read-local! legacy)
                                    (fn [_] (swap! *heavy-read-errors conj account) nil))])
        (.then (fn [stores]
                 (let [local (aget stores 0)
                       backup (aget stores 1)
                       legacy' (if (some heavy-id? (keys local)) text legacy)]
                   (when legacy'
                     (swap! *legacy-text assoc account legacy')
                     (when (= legacy' legacy) (swap! *legacy-backed conj account)))
                   ;; The backup contributes heavy entries only. Current small
                   ;; preferences, including removals, belong to the main key.
                   (merge (into {} (filter (comp heavy-id? key)) backup) local)))))))
(defonce *write-tick (atom nil))
(def max-bytes (* 2 1024 1024))
(defn owner [options] (if (= :public (:scope options)) :public @*identity))
(defn storage-key [_ options]
  (when-let [owner (owner options)] (str prefix (pr-str owner))))
(defn- warn! []
  (status/fail! :component-storage "Local data could not be saved"
                "Browser storage is unavailable or full. Your current page still works." nil))

(declare schedule-write! mark-return!)

(defn migrate-component-owners
  "Move persisted component IDs to the generic namespace without changing saved
   values or expiry. An existing destination entry wins over its legacy entry."
  [bucket]
  (let [legacy-prefix (str "tolgraven" ".")]
    (reduce-kv
      (fn [result id snapshot]
        (let [[kind path] (when (vector? id) id)
              component-owner (when (vector? path) (get path 1))]
          (if (and (= :state kind) (vector? path) (= :component (first path))
                   (string? component-owner) (string/starts-with? component-owner legacy-prefix))
            (let [id' (assoc id 1 (assoc path 1 (str "website." (subs component-owner (count legacy-prefix)))))]
              (cond-> (dissoc result id)
                (not (contains? bucket id')) (assoc id' snapshot)))
            result)))
      bucket bucket)))

(defn ready!
  "Queue one disk read per owner. Failed private reads cannot overwrite unseen
   durable data; public caches may recover through the ordinary server source."
  ([] (ready! {:scope :public}))
  ([options]
   (if-let [account (when (exists? js/window) (owner options))]
     (or (get @*reads account)
         (let [key (storage-key nil options)
               failed-read! (fn [_] (swap! *read-errors conj account) missing)
               failed-heavy-read! (fn [_] (swap! *heavy-read-errors conj account) missing)
               promise
               (-> (validation/when-ready!)
                   (.then (fn [_]
                            (js/Promise.all
                              #js [(js/Promise.
                                     (fn [resolve _]
                                       (js/setTimeout
                                         #(resolve (try
                                                     (.catch (read-local-owner! account key) failed-read!)
                                                     (catch :default error (failed-read! error)))) 0)))
                                   (.catch (disk/read! account) failed-heavy-read!)])))
                   (.then (fn [stores]
                            (let [local (aget stores 0)
                                  heavy (aget stores 1)
                                  local' (when-not (identical? local missing) local)
                                  heavy' (when-not (identical? heavy missing) heavy)
                                  failed? (identical? local missing)
                                  saved (merge {} heavy' local')]
                              (when (or (seq heavy')
                                        (some heavy-id? (keys saved)))
                                (swap! *heavy-owners conj account))
                              ;; Retain the legacy copy until its IDB write commits.
                              (when (or (contains? @*legacy-text account)
                                        (some heavy-id? (keys local')))
                                (swap! *migrating conj account)
                                (swap! *dirty conj account))
                              (-> (disk/map-chunks!
                                    (fn [[id snapshot]]
                                      (if (nil? (validation/explain schema/storage-snapshot snapshot))
                                        [id snapshot]
                                        (do (when-not (contains? local' id)
                                              (swap! *heavy-read-errors conj account))
                                            nil))) saved)
                                  (.then
                                    (fn [entries]
                                      (let [clean (into {} (keep identity entries))
                                            migrated (migrate-component-owners clean)]
                                        (when (and (not= account :public)
                                                   (some #(not (contains? clean %)) (keys local')))
                                          (throw (js/Error. "Invalid private snapshots")))
                                        ;; Writes/removals queued before this read win.
                                        (swap! *buckets update account
                                               #(merge (into {} (remove (fn [[id _]]
                                                                          (affected? id (get @*deleted-paths account)))) migrated) %))
                                        (when (or (not= saved migrated)
                                                  (some #(affected? % (get @*deleted-paths account)) (keys migrated))
                                                  (some (fn [[_ snapshot]]
                                                          (<= (:expires-at snapshot 0) (.now js/Date))) migrated))
                                          (swap! *dirty conj account))
                                        (swap! *deleted-paths dissoc account)
                                        ;; A heavy-cache failure cannot disable readable
                                        ;; localStorage drafts or authorize an IDB overwrite.
                                        (when-not (contains? @*heavy-read-errors account)
                                          (swap! *heavy-ready conj account))
                                        (when (or (not failed?) (= account :public))
                                          (swap! *ready conj account))
                                        (when (contains? @*dirty account) (schedule-write!))
                                        nil)))))))
                   (.catch (fn [error]
                             (failed-read! error)
                             (when (= account :public) (swap! *ready conj account))
                             nil)))]
           (swap! *reads assoc account promise)
           promise))
     (js/Promise.resolve nil))))

(defn read-failed? [options] (contains? @*read-errors (owner options)))

(defn retry-read!
  "Retry a completed failed owner read through the same deduplicated boundary."
  [options]
  (let [account (owner options)]
    (when (or (contains? @*read-errors account) (contains? @*heavy-read-errors account))
      (swap! *read-errors disj account)
      (swap! *heavy-read-errors disj account)
      (swap! *heavy-ready disj account)
      (swap! *reads dissoc account)
      (swap! *ready disj account))
    (ready! options)))

(defn read! [id options]
  (ready! options)
  (let [account (owner options)
        snapshot (get-in @*buckets [account id])]
    (when (and snapshot (not= false (:consume? options)) (not (contains? (get @*consumed account) id)))
      (swap! *consumed update account (fnil conj #{}) id)
      (swap! *dirty conj account)
      (schedule-write!))
    (when (and (= 1 (:version snapshot))
               (= (or (:version options) 1) (:schema snapshot))
               (> (:expires-at snapshot 0) (.now js/Date))
               (contains? snapshot :value))
      snapshot)))

(defn write! [id value options]
  (when-let [account (owner options)]
    (ready! options)
    (let [previous (get-in @*buckets [account id])
          schema (or (:version options) 1)]
      ;; Repeated dumps do not serialize or extend expiry for unchanged data.
      (when-not (and (= schema (:schema previous)) (= value (:value previous))
                     (> (:expires-at previous 0) (.now js/Date)))
        (swap! *consumed update account disj id)
        (swap! *buckets assoc-in [account id]
               {:version 1 :schema schema
                :expires-at (+ (.now js/Date) (or (:ttl-ms options) 1800000)) :value value})
        (swap! *dirty conj account)
        (schedule-write!))
      true)))

(defn consume-restored!
  "A local document already installed these paths. Consume their older public
   envelope entries without re-applying values over its exact hydration state."
  [state]
  (doseq [[id _] (get @*buckets :public)
          :let [paths (snapshot-paths id)]
          :when (and (seq paths)
                     (every? #(not (identical? missing (get-in state % missing))) paths))]
    (read! id {:scope :public})))
(defn remove! [id options]
  (when-let [account (owner options)]
    (ready! options)
    ;; Tombstone prevents an in-flight disk read resurrecting removed data.
    (when-not (and (contains? (get @*buckets account) id)
                   (nil? (get-in @*buckets [account id])))
      (swap! *buckets assoc-in [account id] nil)
      (swap! *dirty conj account)
      (schedule-write!))))

(defn- saved! [account bucket]
  (when (= account :public)
    (reset! *public-saved? (contains? bucket :public-content))
    (when @*public-saved?
      (try (mark-return! true) (catch :default _ nil))))
  (status/recover! :component-storage))

(defn- failed! [account]
  (swap! *disk-generations update account (fnil inc 0))
  (swap! *dirty conj account)
  (when (= account :public)
    (reset! *public-saved? false)
    (try (mark-return! false) (catch :default _ nil)))
  (warn!))

(defn- write-heavy! [account bucket]
  (let [generation (get (swap! *disk-generations update account (fnil inc 0)) account)
        current? #(= generation (get @*disk-generations account))
        previous (get @*disk-writes account (js/Promise.resolve nil))
        pending (-> previous
                    (.catch (fn [_] nil))
                    (.then (fn [_]
                             (when (current?)
                               (disk/write! account (into {} (filter (comp heavy-id? key)) bucket)))))
                    (.then (fn [_]
                             (when (current?)
                               ;; Small saves already committed independently. Only
                               ;; the legacy backup waits for the heavy transaction.
                               (when (contains? @*migrating account)
                                 (remove-disk! (migration-key account)))
                               (swap! *migrating disj account)
                               (swap! *legacy-text dissoc account)
                               (swap! *legacy-backed disj account)
                               (saved! account bucket))))
                    (.catch (fn [_] (when (current?) (failed! account)))))]
    (swap! *disk-writes assoc account pending)
    pending))

(defn drain!
  "Persist small preferences synchronously and return each owner's actual result.
   Heavy caches use serial async IDB writes; pagehide never encodes large content."
  []
  (when @*write-tick (js/clearTimeout @*write-tick) (reset! *write-tick nil))
  (let [accounts (filterv @*ready @*dirty)]
    (swap! *dirty #(apply disj % accounts))
    (into {}
          (map (fn [account]
                 (try
                   (let [bucket (into {} (filter (fn [[id v]]
                                                  (and (not (contains? (get @*consumed account) id))
                                                       (> (:expires-at v 0) (.now js/Date)))))
                                      (get @*buckets account))
                         heavy? (or (contains? @*heavy-owners account)
                                    (contains? @*migrating account)
                                    (some heavy-id? (keys bucket)))
                         small (if heavy? (into {} (remove (comp heavy-id? key)) bucket) bucket)
                         text (pr-str small)]
                     (when (> (count text) max-bytes) (throw (js/Error. "Snapshot too large")))
                     ;; Small preferences retain the synchronous EDN validation contract.
                     (reader/read-string text)
                     ;; Copy the original encoded envelope once, without encoding
                     ;; its heavy values again on each preference/pagehide save.
                     (when (and (contains? @*migrating account)
                                (not (contains? @*legacy-backed account)))
                       (write-disk! (migration-key account) (get @*legacy-text account))
                       (swap! *legacy-backed conj account))
                     (write-disk! (str prefix (pr-str account)) text)
                     (if heavy?
                       (do
                         (swap! *heavy-owners conj account)
                         (if (contains? @*heavy-ready account)
                           (write-heavy! account bucket)
                           ;; Preserve the unseen heavy store until a successful
                           ;; retry. Small preferences are already durable.
                           (failed! account)))
                       (saved! account bucket))
                     ;; Explicit draft saves acknowledge the synchronous small
                     ;; write. Heavy cache callers await drain-async! separately.
                     [account true])
                   (catch :default _ (failed! account) [account false])))
               accounts))))

(defn drain-async!
  "Drain preferences, then await all queued heavy writes for explicit callers."
  []
  (drain!)
  (js/Promise.all (into-array (vals @*disk-writes))))

(defn schedule-write! []
  (when (and (exists? js/window) (nil? @*write-tick))
    (reset! *write-tick
            (js/setTimeout
             #(-> (js/Promise.all (into-array (vals @*reads))) (.then (fn [_] (drain!)))) 0))))

(defn- write-tracked! [id read options]
  (let [value (read)]
    (if (identical? missing value) (remove! id options) (write! id value options))))

(defn flush!
  ([] (flush! :all))
  ([kind]
   (when @*pending (js/clearTimeout @*pending) (reset! *pending nil))
   (doseq [[id {:keys [read options owner]}] @*tracked
           :when (and (= owner (website.component.storage/owner options))
                      (or (#{:all :navigation} kind)
                          (and (= :state kind) (vector? id) (= :state (first id)))
                          (and (= :content kind) (or (= id :public-content)
                                                   (and (vector? id) (= :resource (first id)))))))]
     ;; A previous debounce can expire while a newly mounted instance is still
     ;; restoring. Do not snapshot its defaults over the pending disk read.
     (if (contains? @*ready owner)
       (do
         (when (and (= :navigation kind) (contains? (get @*consumed owner) id))
           (swap! *consumed update owner disj id)
           (swap! *dirty conj owner))
         (write-tracked! id read options))
       (-> (ready! options)
           (.then (fn [_]
                    (when (and (contains? @*ready owner)
                               (= owner (website.component.storage/owner options)))
                      (write-tracked! id read options)))))))))
(defn schedule! []
  (when (and (exists? js/window) (nil? @*pending))
    (reset! *pending (js/setTimeout flush! 150))))
(defn track! [id read options]
  (ready! options)
  (swap! *tracked assoc id {:read read :options options :owner (owner options)}))
(defn untrack! [id]
  (swap! *tracked dissoc id))

(defn identity! [identity]
  (when-not (= identity @*identity)
    (flush!)
    (swap! *tracked #(into {} (filter (fn [[_ entry]] (= :public (:scope (:options entry))))) %))
    (reset! *identity identity)
    (ready! {:scope :user})))
(defn mark-return! [saved?]
  ;; A hint only: no content or view state is sent to the server. Keep a bounded
  ;; recent-path list so returning through several documents also avoids SSR.
  (let [previous (try
                   (when-let [[_ value] (re-find #"(?:^|;\s*)tolgraven-return=([^;]*)" (.-cookie js/document))]
                     (let [decoded (js/decodeURIComponent value)]
                       (if (= "/" (first decoded)) [decoded]
                         (let [paths (js->clj (js/JSON.parse decoded))]
                           (when (vector? paths) (filterv string? paths))))))
                   (catch :default _ nil))
        paths (take 16 (distinct (cons (.-pathname js/location) previous)))
        ;; Keep the cookie well below browser limits, including URI encoding.
        value (loop [paths (vec paths)]
                (let [encoded (js/encodeURIComponent (js/JSON.stringify (clj->js paths)))]
                  (if (and (> (count encoded) 3000) (seq paths))
                    (recur (pop paths)) encoded)))]
    (set! (.-cookie js/document)
          (str "tolgraven-return=" value
               "; Max-Age=" (if saved? 1800 0) "; Path=/; SameSite=Lax"))))

(defn save-navigation! []
  ;; Capture the current page before serializing tracked state. A separate
  ;; visibility listener may run after this flush, leaving the previous offset
  ;; in the snapshot used by a full-document browser Back.
  (r/dispatch-sync [:scroll/save-history])
  (flush! :navigation)
  (drain!)
  ;; Only mark a return after the consolidated public write succeeds.
  (try (mark-return! @*public-saved?) (catch :default _ nil)))

(defn- removed-paths
  "Walk only changed map branches; unchanged subtrees retain their identity."
  [before after path]
  (when (and (map? before) (not (identical? before after)))
    (mapcat (fn [[key value]]
              (let [child (conj path key)]
                (if (and (map? after) (contains? after key))
                  (removed-paths value (get after key) child)
                  [child]))) before)))

(defn mirror-deletions! [before after]
  (when-let [removed (seq (removed-paths before after []))]
    (doseq [account (distinct (remove nil? [:public @*identity]))]
      ;; Cold disk reads must also honor deletions made before their envelope arrived.
      (when-not (contains? @*ready account)
        (swap! *deleted-paths update account (fnil into #{}) removed)
        (ready! {:scope (if (= account :public) :public :user)}))
      (let [options {:scope (if (= account :public) :public :user)}
            ids (into (set (keys (get @*buckets account)))
                      (keep (fn [[id entry]] (when (= account (:owner entry)) id))) @*tracked)]
        (doseq [id ids :when (affected? id removed)]
          (let [path (when (and (vector? id) (= :state (first id))) (second id))
                absent (js-obj) value (if path (get-in after path absent) absent)]
            (if (identical? absent value)
              (remove! id options)
              ;; Keep surviving sibling state, but replace the old snapshot now.
              ;; Disk IO still belongs to the shared write queue.
              (write! id value (or (get-in @*tracked [id :options]) options)))))))))

(r/reg-fx :component-storage/commit
  (fn [{:keys [before after]}]
    (when (exists? js/window)
      (mirror-deletions! before after)
      (when (some #(not (identical? (get before %) (get after %)))
                  [:component :module :page :state])
        (schedule!)))))

(r/reg-fx :component-storage/flush
  (fn [mode]
    (flush! mode)
    (drain!)))

(r/reg-global-interceptor
  (r/->interceptor
    :id :component-storage/commit
    :after (fn [context]
             ;; Validation can reject a transaction by discarding its effects.
             ;; Capture accepted values here; effects run after the :db commit.
             (if (contains? (:effects context) :db)
               (update-in context [:effects :fx] (fnil conj [])
                          [:component-storage/commit
                           {:before (r/get-coeffect context :db)
                            :after (r/get-effect context :db)}])
               context))))


(def public-cache-paths [[:store :public] [:state :motion-seen]])
(def public-cache-options {:scope :public :ttl-ms 1800000})

(r/reg-event-db :component-storage/restore-public
  {:args [:tuple [:vector [:tuple [:vector :keyword] :any]]]}
  (fn [db [_ snapshots]]
    (reduce (fn [db [path value]]
              (if (some? (get-in db path)) db (assoc-in db path value)))
            db snapshots)))

(defn restore-public-cache!
  "Restore shared caches after bootstrap state installation. Feature caches
   install with their own modules, before those modules become renderable."
  []
  (r/dispatch-sync
    [:component-storage/restore-public
     (vec (keep (fn [path]
                  (when-let [saved (read! [:state path] public-cache-options)]
                    [path (:value saved)])) public-cache-paths))])
  (doseq [path public-cache-paths]
    (track! [:state path] #(state-value path) public-cache-options)))
