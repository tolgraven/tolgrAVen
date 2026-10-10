(ns tolgraven.cache.inflight
  "Bounded promise entries: pending work is never an eviction victim.")

(def entry-schema
  [:map
   [:value [:fn #(and (instance? clojure.lang.IPending %)
                     (instance? clojure.lang.IDeref %))]]
   [:expires-at :int]])
(def acquisition-schema [:tuple :boolean [:maybe entry-schema]])

(defn acquire!
  "Return [owner? entry]. A full cache containing only pending work returns
   [false nil]; callers provide their ordinary unavailable/retry response.
   Expiry prunes completed entries only. Hold the lock just for bookkeeping."
  [*cache cache-key now ttl-ms limit]
  (locking *cache
    (let [entries (into {} (filter (fn [[_ {:keys [value expires-at]}]]
                                    (or (not (realized? value)) (> expires-at now)))) @*cache)]
      (reset! *cache entries)
      (if-let [entry (get entries cache-key)]
        [false entry]
        (let [victims (->> entries
                           (filter #(realized? (:value (val %))))
                           (sort-by (comp :expires-at val))
                           (take (max 0 (inc (- (count entries) limit))))
                           (map key))
              entries (apply dissoc entries victims)]
          (if (>= (count entries) limit)
            [false nil]
            (let [entry {:value (promise), :expires-at (+ now ttl-ms)}]
              (reset! *cache (assoc entries cache-key entry))
              [true entry])))))))

(defn shorten!
  "Shorten only this acquisition's expiry, serialized with entry pruning."
  [*cache cache-key entry expires-at]
  (locking *cache
    (swap! *cache
      (fn [entries]
        (if (identical? (:value entry) (get-in entries [cache-key :value]))
          (update-in entries [cache-key :expires-at] min expires-at)
          entries)))))
