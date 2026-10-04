(ns tolgraven.component.storage
  "Opt-in snapshots, one disk envelope per owner. Components only touch memory."
  (:require [cljs.reader :as reader]
            [reagent.core :as r]
            [re-frame.db :as rfdb]
            [tolgraven.service-status :as status]))

(def missing (js-obj))
(def prefix "tolgraven.component.v2:")
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
(defn read-disk! [key] (.getItem js/localStorage key))
(defn write-disk! [key value] (.setItem js/localStorage key value))
(defonce *write-tick (atom nil))
(def max-bytes (* 2 1024 1024))
(defn owner [options] (if (= :public (:scope options)) :public @*identity))
(defn storage-key [_ options]
  (when-let [owner (owner options)] (str prefix (pr-str owner))))
(defn- warn! []
  (status/fail! :component-storage "Local data could not be saved"
                "Browser storage is unavailable or full. Your current page still works." nil))

(declare schedule-write!)

(defn ready!
  "Queue one disk read per owner per document, shared by every caller. Startup
   awaits this before rendering; subsequent component reads are memory-only."
  ([] (ready! {:scope :public}))
  ([options]
   (if-let [account (owner options)]
     (or (get @*reads account)
         (let [key (storage-key nil options)
               promise (js/Promise.
                        (fn [resolve _]
                          (js/setTimeout
                           (fn []
                             (let [saved (try
                                           (when-let [text (read-disk! key)]
                                             (when (<= (count text) max-bytes)
                                               (let [value (reader/read-string text)]
                                                 (when (map? value) value))))
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
          (status/recover! :component-storage))
        (catch :default _ (warn!))))))
(defn schedule-write! []
  (when-not @*write-tick
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
  (when-not @*pending (reset! *pending (js/setTimeout flush! 150))))
(defn track! [id read options]
  (ready! options)
  (swap! *tracked assoc id {:read read :options options :owner (owner options)}))
(defn identity! [identity]
  (when-not (= identity @*identity)
    (flush!)
    (swap! *tracked #(into {} (filter (fn [[_ entry]] (= :public (:scope (:options entry))))) %))
    (reset! *identity identity)
    (ready! {:scope :user})))
(defonce listeners
  (when (exists? js/window)
    (let [save! #(do (flush! :navigation) (drain!))]
      (.addEventListener js/window "pagehide" (fn [_] (save!)))
      (.addEventListener js/document "visibilitychange"
                         (fn [_] (when (= "hidden" (.-visibilityState js/document)) (save!)))))))

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
           (fn [_ _ before after] (mirror-deletions! before after)))
