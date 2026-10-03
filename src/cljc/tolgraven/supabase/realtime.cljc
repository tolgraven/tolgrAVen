(ns tolgraven.supabase.realtime
  (:require [clojure.string :as string]
            [tolgraven.supabase.query :as query]))

(def tables
  {"site_users" {:seed-key :users :key [:id]}
   "blog_posts" {:seed-key :blog_posts :key [:id]}
   "blog_comments" {:seed-key :blog_comments :key [:id]}
   "chat_messages" {:seed-key :chat_messages :key [:message_id]}
   "user_documents" {:seed-key :store_documents :key [:owner_id :collection :doc_id]}})

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
