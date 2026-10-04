(ns tolgraven.supabase.plan
  "Pure evaluation of module-owned read graphs. Adapters supply batched reads or
   managed re-frame subscriptions; nil is pending, {:docs []} is completed empty."
  (:require [tolgraven.supabase.query :as query]))

(defn rows [results] (vec (mapcat #(map :data (:docs %)) results)))

(defn evaluate [nodes context read-many]
  (loop [values context cache {} attempted #{}]
    (let [ready (filterv (fn [{:keys [id depends]}]
                          (and (not (contains? attempted id))
                               (every? #(contains? values %) (if (fn? depends) (depends values) depends)))) nodes)]
      (if (empty? ready)
        {:values values :cache cache :ready? (every? #(contains? values (:id %)) nodes)}
        (let [requests (mapv (fn [node] [node (vec ((:queries node) values))]) ready)
              queries (vec (distinct (mapcat second requests)))
              responses (zipmap queries (read-many queries))
              values (reduce (fn [values [{:keys [id transform]} queries]]
                               (let [results (mapv responses queries)]
                                 (if (every? some? results)
                                   (assoc values id ((or transform (fn [_ values] (rows values))) values results))
                                   values))) values requests)
              cache (into cache (keep (fn [[opts result]]
                                       (when (some? result) [(pr-str (query/normalize-query opts)) result]))) responses)]
          (recur values cache (into attempted (map :id ready))))))))
