(ns tolgraven.modules.blog.cache
  "Restore public caches before client mounts, or after network SSR hydration.
   All persistence uses the shared envelope; no component performs disk IO."
  (:require [cljs.reader :as reader]
            [tolgraven.component.legacy-storage :as legacy]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [tolgraven.react :as rf]
            [tolgraven.component.storage :as storage]
            [tolgraven.component.restore :as restore]
            [tolgraven.browser-resources :as resources]
            [tolgraven.render-context :as context]
            [tolgraven.supabase.query :as query]
            [tolgraven.schema.common :as c]))

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

(rf/reg-sub :blog/cache-queries
  {:args [:tuple], :result [:map-of c/path :any]}
  :<- [:component-storage/value [:store :scoped]]
  (fn [scoped _]
    ;; Parsing persisted EDN keys belongs to the query-cache dependency alone.
    (into {} (keep (fn [[key value]]
                     (when (public-query? key) [[:store :scoped key] value])))
          (when (map? scoped) scoped))))

(rf/reg-sub :blog/cache-values
  {:args [:tuple], :result [:map-of c/path :any]}
  (fn [_]
    (into [(rf/subscribe [:blog/cache-queries])]
          (map #(rf/subscribe [:component-storage/value %])) state-paths))
  (fn [[queries & display] _]
    (into queries (map vector state-paths display))))

(defonce *tracking (atom nil))
(defonce *restore-cleanup (atom nil))

(defn stop! []
  (when-let [cleanup! @*restore-cleanup]
    (reset! *restore-cleanup nil)
    (cleanup!))
  (when-let [{:keys [reaction paths]} @*tracking]
    (ratom/dispose! reaction)
    (doseq [path @paths] (storage/untrack! [:state path]))
    (reset! *tracking nil)))

(defn start! []
  (stop!)
  (let [*paths (atom #{})
        reaction (r/track!
                   (fn []
                     (let [values (rf/subscribe [:blog/cache-values])
                           current @values]
                       (doseq [path (keys current) :when (not (contains? @*paths path))]
                         (swap! *paths conj path)
                         (storage/track! [:state path] #(get @values path storage/missing) options))
                       (storage/schedule!))))]
    ;; This cache lives with the installed browser module, including while its
    ;; pages are unmounted. Explicit disposal releases the subscription owner.
    (reset! *tracking {:reaction reaction :paths *paths})))

(rf/reg-event-db :blog/restore-cache
  {:args [:tuple [:vector [:tuple c/path :any]] :boolean]}
  (fn [db [_ snapshots restore-ui?]]
    (reduce (fn [db [path value]]
              (if (some #{path} state-paths)
                ;; The server owns content, not this browser's display choices.
                ;; An interaction since startup wins even if it returns to a default.
                (if (and restore-ui? (map? value))
                  (reduce-kv (fn [db key value]
                               (if (contains? (get-in db [:state :blog :restore-edits] #{})
                                              [(last path) key])
                                 db
                                 (assoc-in db (conj path key) value))) db value)
                  db)
                (if (some? (get-in db path)) db (assoc-in db path value)))) db snapshots)))

(defn restore!
  "Restore after the single validated disk read; network SSR may already be hydrated."
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
    (rf/dispatch-sync [:blog/restore-cache (vec snapshots) (not (restore/local-document?))])
    (when (contains? old-state :blog) (legacy/write! (dissoc old-state :blog)))
    (start!)))

(defn install! []
  (stop!)
  (let [ready (storage/ready!)]
    (if (and (:hydrate? @restore/*context)
             (:route-parameters @context/*snapshot))
      ;; Do not track server defaults while disk choices are waiting: tracking
      ;; could persist those defaults over the very choices we intend to restore.
      ;; The shared gate lets the SSR page commit before changing its display.
      (let [*active? (atom true)
            cancel! (resources/after-page!
                      (fn []
                        (-> ready
                            (.then (fn [_] (when @*active? (restore!))))
                            (.catch (fn [_] nil)))))]
        (reset! *restore-cleanup #(do (reset! *active? false) (cancel!)))
        nil)
      (-> ready (.then (fn [_] (restore!)))))))
