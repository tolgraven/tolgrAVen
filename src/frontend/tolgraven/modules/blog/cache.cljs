(ns tolgraven.modules.blog.cache
  "Restore public query results and display state before mounting any blog view.
   All persistence uses the shared envelope; no component performs disk IO."
  (:require [cljs.reader :as reader]
            [tolgraven.component.legacy-storage :as legacy]
            [re-frame.db :as rfdb]
            [tolgraven.react :as rf]
            [tolgraven.component.storage :as storage]
            [tolgraven.supabase.query :as query]))

(def options {:scope :public :ttl-ms 1800000})
(def state-paths [[:state :blog :comment-limit]
                 [:state :blog :comments-expanded]
                 [:state :blog :comment-thread-expanded]
                 [:state :blog :adding-comment]])

(defn public-query? [key]
  (try
    (let [opts (reader/read-string key)]
      (and (map? opts) (:scoped? opts)
           (#{"blog-posts" "blog-comments" "users"}
            (query/path-part (first (or (:path-collection opts) (:path-document opts)))))))
    (catch :default _ false)))

(defn query-path? [path]
  (and (vector? path) (= 3 (count path))
       (= [:store :scoped] (subvec path 0 2)) (public-query? (last path))))

(defn- track-path! [path]
  (storage/track! [:state path] #(get-in @rfdb/app-db path storage/missing) options))

(rf/reg-event-db :blog/restore-cache
  (fn [db [_ snapshots]]
    (reduce (fn [db [path value]]
              ;; A newer SSR snapshot (or an already completed query) wins.
              (if (some? (get-in db path)) db (assoc-in db path value))) db snapshots)))

(declare install-watch!)

(defn restore!
  "Called after the single storage read and SSR installation, before mounting."
  []
  (let [old-state (legacy/read!)
        paths (into state-paths
                    (keep (fn [id]
                            (when (and (vector? id) (= :state (first id))
                                       (query-path? (second id))) (second id))))
                    (keys (get @storage/*buckets :public)))
        snapshots (keep (fn [path]
                          (let [saved (storage/read! [:state path] options)
                                old-value (when (= [:state :blog] (subvec path 0 2))
                                            (get-in old-state (subvec path 1)))]
                            (cond saved [path (:value saved)]
                                  (some? old-value) [path old-value]))) paths)]
    (rf/dispatch-sync [:blog/restore-cache (vec snapshots)])
    (when (contains? old-state :blog) (legacy/write! (dissoc old-state :blog)))
    (doseq [path paths] (track-path! path))
    (install-watch!)))

(defn install-watch! []
  (add-watch rfdb/app-db ::cache
    (fn [_ _ before after]
      (when-not (identical? (get-in before [:store :scoped]) (get-in after [:store :scoped]))
        (doseq [key (keys (get-in after [:store :scoped])) :when (public-query? key)]
          (track-path! [:store :scoped key]))
        (storage/schedule!)))))

(defn install! []
  (-> (storage/ready!) (.then (fn [_] (restore!)))))
