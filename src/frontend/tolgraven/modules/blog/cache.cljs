(ns tolgraven.modules.blog.cache
  "Restore public query results and display state before mounting any blog view.
   All persistence uses the shared envelope; no component performs disk IO."
  (:require [cljs.reader :as reader]
            [tolgraven.component.legacy-storage :as legacy]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
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

(rf/reg-sub :blog/cache-values
  (fn [db _]
    (into (into {} (map (fn [path] [path (get-in db path storage/missing)])) state-paths)
          (keep (fn [[key value]]
                  (when (public-query? key) [[:store :scoped key] value])))
          (get-in db [:store :scoped]))))

(defonce *tracking (atom nil))

(defn stop! []
  (when-let [{:keys [reaction paths]} @*tracking]
    (ratom/dispose! reaction)
    (doseq [path @paths] (storage/untrack! [:state path]))
    (reset! *tracking nil)))

(defn start! []
  (stop!)
  (let [values (rf/subscribe [:blog/cache-values])
        *paths (atom #{})
        reaction (r/track!
                   (fn []
                     (let [current @values]
                       (doseq [path (keys current) :when (not (contains? @*paths path))]
                         (swap! *paths conj path)
                         (storage/track! [:state path] #(get @values path storage/missing) options))
                       (storage/schedule!))))]
    ;; This cache lives with the installed browser module, including while its
    ;; pages are unmounted. Explicit disposal releases the subscription owner.
    (reset! *tracking {:reaction reaction :paths *paths})))

(rf/reg-event-db :blog/restore-cache
  (fn [db [_ snapshots]]
    (reduce (fn [db [path value]]
              ;; A newer SSR snapshot (or an already completed query) wins.
              (if (some? (get-in db path)) db (assoc-in db path value))) db snapshots)))

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
    (start!)))

(defn install! []
  (-> (storage/ready!) (.then (fn [_] (restore!)))))
