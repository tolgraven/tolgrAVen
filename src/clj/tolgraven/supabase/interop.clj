(ns tolgraven.supabase.interop
  (:require
   [tolgraven.platform.supabase :as supabase]
   [tolgraven.store.contract :as contract]
   [tolgraven.supabase.store :as store]
   [tolgraven.supabase.query :as query]
   [clojure.string :as string]))

(defn fetch-contract
  ([] (fetch-contract nil))
  ([_]
   (contract/seed->contract (supabase/fetch-seed))))

(defn changed-rows [before after table]
  (let [{:keys [seed-key on-conflict]} (supabase/table-config table)
        columns (mapv keyword (string/split on-conflict #","))
        row-key #(mapv % columns)
        old (into {} (map (juxt row-key identity)) (get before seed-key))
        new (into {} (map (juxt row-key identity)) (get after seed-key))]
    ;; Replacement of a multi-row legacy document must never silently delete
    ;; rows. It needs a dedicated transactional operation before we support it.
    (when (some #(not (contains? new %)) (keys old))
      (throw (ex-info "Row removal requires a dedicated transactional operation"
                      {:table table})))
    (filterv #(not= % (get old (row-key %))) (get after seed-key))))

(defn write-document!
  ([path data merge-fields]
   (write-document! nil path data merge-fields))
  ([_ path data merge-fields]
   (let [path (mapv query/path-part path)
         current (fetch-contract)
         updated (store/set-document current path data merge-fields)
         before (contract/contract->seed current)
         after (contract/contract->seed updated)
         ;; Realize all validation before the first mutation.
         changes (mapv (fn [table] [table (changed-rows before after table)])
                       supabase/table-order)]
     (when (= "blog-post-ids" (first path))
       (throw (ex-info "Post IDs are derived from blog_posts and cannot be written" {})))
     (when (and (= "blog-posts" (first path)) (contains? data :comments))
       (throw (ex-info "Write comments through blog-comments, not nested blog-posts" {})))
     (doseq [[table rows] changes :when (seq rows)]
       (supabase/upsert-rest! table rows))
     (get-in updated path))))
