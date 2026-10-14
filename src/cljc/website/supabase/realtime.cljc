(ns website.supabase.realtime
  (:require [clojure.string :as string]
            [website.supabase.query :as query]
            [website.supabase.catalog :as catalog]))

(def tables
  (into {} (keep (fn [{:keys [table seed-key cache-key key public-columns]}]
                   (when public-columns
                     [(name table) {:seed-key (or cache-key seed-key table), :key key}])))
        (mapcat val catalog/definitions)))

(def empty-seed (zipmap (map :seed-key (vals tables)) (repeat [])))

(defn public-row [table row]
  (select-keys row (map keyword (string/split (get query/public-columns table "") #","))))

(defn apply-change
  "Apply a streamed row to the shared cache. DELETE needs only primary keys."
  [seed {:keys [table eventType new old]}]
  (if-let [{:keys [seed-key key]} (tables table)]
    (let [row (public-row table (if (= eventType "DELETE") old new))
          identity (mapv row key)
          old-identity (when (and (= eventType "UPDATE") (every? #(some? (get old %)) key))
                         (mapv old key))
          rows (get seed seed-key [])]
      (if (or (not (#{"INSERT" "UPDATE" "DELETE"} eventType)) (some nil? identity))
        seed
        (assoc seed seed-key
               (let [matches? #(contains? (cond-> #{identity} old-identity (conj old-identity)) (mapv % key))
                     previous (first (filter matches? rows))
                     remaining (filterv (complement matches?) rows)]
                 (if (= eventType "DELETE") remaining
                     (conj remaining (merge previous row)))))))
    seed))

(defn install-snapshot [seed table rows buffered]
  (let [seed-key (:seed-key (tables table))]
    (reduce apply-change
            (assoc seed seed-key (mapv #(public-row table %) rows)) buffered)))
