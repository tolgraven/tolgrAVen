(ns tolgraven.modules.blog.ssr
  "Public blog snapshot compatibility, shared by Node rendering and browser hydration."
  (:require [tolgraven.modules.blog.comments :as comments]
            [tolgraven.modules.blog.data :as data]
            [tolgraven.supabase.query :as query]))

(defn- records [rows]
  (into {} (keep (fn [row] (when (:id row) [(str (:id row)) (dissoc row :author :date)]))) rows))

(defn- result [rows]
  {:docs (mapv #(hash-map :id (str (:id %)) :data (dissoc % :author :date)) rows)})

(defn- query-key [opts] (pr-str (query/normalize-query opts)))

(defn- legacy-queries [snapshot]
  ;; Current acquisition plans supply exact caches in :app-db-edn. Older public
  ;; snapshots and renderer fixtures carry display rows, so reconstruct only the
  ;; queries represented by those rows. Never mark a whole table loaded.
  (when-not (:app-db-edn snapshot)
    (into {}
          (concat
            (when (:summaries snapshot) [[(query-key data/summaries-query) (result (:summaries snapshot))]])
            (when (contains? snapshot :comments)
              (for [{:keys [post-id parent-id]}
                    (concat (map #(hash-map :post-id (:id %) :parent-id nil) (:posts snapshot))
                            (or (:comment-parents snapshot)
                                (for [row (:comments snapshot) :when (nil? (:parent-comment row))]
                                  {:post-id (:parent-post row) :parent-id (:id row)})))
                    :let [opts (if parent-id (comments/thread-query post-id parent-id)
                                   (comments/root-query post-id comments/page-size))]]
                [(query-key opts)
                 (result (filter #(and (= post-id (:parent-post %)) (= parent-id (:parent-comment %)))
                                 (:comments snapshot)))]))
            (when (and (:missing? snapshot) (:post-id snapshot))
              [[(query-key (data/post-query (:post-id snapshot))) {:docs []}]])
            (when (and (:page snapshot) (not (:post-id snapshot)))
              [[(query-key (data/page-query (dec (:page snapshot)) data/page-size)) (result (:posts snapshot))]])
            (for [post (:posts snapshot)] [(query-key (data/post-query (:id post))) (result [post])])))))

(defn public-state [snapshot]
  (let [rows (concat (:posts snapshot) (:comments snapshot))
        public {"users" (records (keep :author rows))
                "blog-posts" (merge (records (:summaries snapshot)) (records (:posts snapshot)))
                "blog-comments" (records (:comments snapshot))}]
    (cond-> {:store {:public public
                    :scoped (merge (into {} (map (fn [[id profile]]
                                                  [(query-key (query/profile-query id))
                                                   {:docs [{:id id :data profile}]}]))
                                          (get public "users"))
                                   (legacy-queries snapshot))}}
      (#{:blog "blog"} (:kind snapshot))
      ;; Fresh SSR display state wins over older disk folds/window sizes. Exact
      ;; public state, when present, is merged over this by the shared contract.
      (assoc :state {:blog {:comment-limit {}
                           :comment-thread-expanded {}
                           :page (dec (or (:page snapshot) 1))
                           :current-post-id (or (:post-id snapshot) (get-in snapshot [:posts 0 :id]))}}))))
