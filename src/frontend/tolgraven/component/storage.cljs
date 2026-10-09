(ns tolgraven.component.storage
  "Opt-in snapshots, one disk envelope per owner. Components only touch memory."
  (:require
    [cljs.reader :as reader]
    [tolgraven.validation :as validation]
    [tolgraven.ssr.schema :as schema]
    [reagent.core :as r]
    [re-frame.db :as rfdb]
    [tolgraven.react :as rf]
    [tolgraven.service-status :as status]))

(def missing (js-obj))
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
(defonce *deleted-paths (atom {}))
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
(defonce *write-tick (atom nil))
(def max-bytes (* 2 1024 1024))
(defn owner [options] (if (= :public (:scope options)) :public @*identity))
(defn storage-key [_ options]
  (when-let [owner (owner options)] (str prefix (pr-str owner))))
(defn- warn! []
  (status/fail! :component-storage "Local data could not be saved"
                "Browser storage is unavailable or full. Your current page still works." nil))

(declare schedule-write! mark-return!)

(defn ready!
  "Queue one disk read per owner per document, shared by every caller. Startup
   awaits this before rendering; subsequent component reads are memory-only."
  ([] (ready! {:scope :public}))
  ([options]
   (if-let [account (when (exists? js/window) (owner options))]
     (or (get @*reads account)
         (let [key (storage-key nil options)
               promise (js/Promise.
                        (fn [resolve _]
                          (js/setTimeout
                           (fn []
                             (let [saved (try
                                           (when-let [text (read-disk! key)]
                                             (when (<= (count text) max-bytes)
                                               (let [value (reader/read-string text)
                                                     clean (when (map? value)
                                                             (into {} (filter (fn [[_ snapshot]]
                                                                                (nil? (validation/explain schema/storage-snapshot snapshot)))) value))]
                                                 (when (not= value clean)
                                                   (swap! *dirty conj account)
                                                   (schedule-write!))
                                                 clean)))
                                           (catch :default _ nil))]
                               ;; Writes/removals queued before this read win.
                               (swap! *buckets update account
                                      #(merge (into {} (remove (fn [[id _]] (affected? id (get @*deleted-paths account)))) saved) %))
                               (when (or (some #(affected? % (get @*deleted-paths account)) (keys saved))
                                         (some (fn [[_ snapshot]]
                                                 (or (not= 1 (:version snapshot))
                                                     (<= (:expires-at snapshot 0) (.now js/Date)))) saved))
                                 (swap! *dirty conj account)
                                 (schedule-write!))
                               (swap! *deleted-paths dissoc account)
                               (swap! *ready conj account)
                               (resolve nil))) 0)))]
           (swap! *reads assoc account promise)
           promise))
     (js/Promise.resolve nil))))

(defn read! [id options]
  (ready! options)
  (let [account (owner options)
        snapshot (get-in @*buckets [account id])]
    (when (and snapshot (not (contains? (get @*consumed account) id)))
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

(defn drain!
  "At most one serialization and setItem per dirty owner. Also used at pagehide:
   browsers may freeze timers there, so the final consolidated write is immediate."
  []
  (when @*write-tick (js/clearTimeout @*write-tick) (reset! *write-tick nil))
  (let [accounts (filterv @*ready @*dirty)]
    (swap! *dirty #(apply disj % accounts))
    (doseq [account accounts]
      (try
        (let [bucket (into {} (filter (fn [[id v]] (and (not (contains? (get @*consumed account) id))
                                                        (> (:expires-at v 0) (.now js/Date)))))
                           (get @*buckets account))
              text (pr-str bucket)]
          (when (> (count text) max-bytes) (throw (js/Error. "Snapshot too large")))
          (reader/read-string text)
          (write-disk! (str prefix (pr-str account)) text)
          (when (= account :public)
            (reset! *public-saved? (contains? bucket :public-content))
            ;; Set the hint on a completed save too: reload requests can begin
            ;; before pagehide. Consuming a snapshot leaves the hint in place;
            ;; pagehide republishes it before the new document reads storage.
            (when @*public-saved?
              (try (mark-return! true) (catch :default _ nil))))
          (status/recover! :component-storage))
        (catch :default _
          (when (= account :public)
            (reset! *public-saved? false)
            (try (mark-return! false) (catch :default _ nil)))
          (warn!))))))
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
           :when (and (= owner (tolgraven.component.storage/owner options))
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
                    (when (= owner (tolgraven.component.storage/owner options))
                      (write-tracked! id read options)))))))))
(defn schedule! []
  (when (and (exists? js/window) (nil? @*pending))
    (reset! *pending (js/setTimeout flush! 150))))
(defn track! [id read options]
  (ready! options)
  (swap! *tracked assoc id {:read read :options options :owner (owner options)}))
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
  (rf/dispatch-sync [:scroll/save-history])
  (flush! :navigation)
  (drain!)
  ;; Only mark a return after the consolidated public write succeeds.
  (try (mark-return! @*public-saved?) (catch :default _ nil)))

(defonce listeners
  (when (exists? js/window)
    (.addEventListener js/window "pagehide" (fn [_] (save-navigation!)))
    (.addEventListener js/document "visibilitychange"
                       (fn [_] (when (= "hidden" (.-visibilityState js/document))
                                 (save-navigation!))))))

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

(add-watch rfdb/app-db ::deletions
           (fn [_ _ before after]
             ;; SSR resets request state too; those deletions are not browser edits.
             (when (exists? js/window) (mirror-deletions! before after))))


(def public-cache-paths [[:store :public] [:state :motion-seen]])
(def public-cache-options {:scope :public :ttl-ms 1800000})

(rf/reg-event-db :component-storage/restore-public
  {:args [:tuple [:vector [:tuple [:vector :keyword] :any]]]}
  (fn [db [_ snapshots]]
    (reduce (fn [db [path value]]
              (if (some? (get-in db path)) db (assoc-in db path value)))
            db snapshots)))

(defn restore-public-cache!
  "Restore shared caches after bootstrap state installation. Feature caches
   install with their own modules, before those modules become renderable."
  []
  (rf/dispatch-sync
    [:component-storage/restore-public
     (vec (keep (fn [path]
                  (when-let [saved (read! [:state path] public-cache-options)]
                    [path (:value saved)])) public-cache-paths))])
  (doseq [path public-cache-paths]
    (track! [:state path] #(get-in @rfdb/app-db path missing) public-cache-options)))
