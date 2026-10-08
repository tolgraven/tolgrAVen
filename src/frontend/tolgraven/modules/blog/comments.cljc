(ns tolgraven.modules.blog.comments
  (:require [tolgraven.supabase.query :as query]))

(def page-size 10)
(defn expanded?
  "Open two reply levels, or one level below a manually expanded thread.
   An explicit fold always takes precedence, including restored view state."
  [expanded path]
  (if-some [value (get expanded path)]
    value
    (or (<= (count path) 3)
        (true? (get expanded (pop path))))))

(defn thread-query [post-id parent-id]
  {:path-collection [:blog-comments] :scoped? true :reply-counts? true
   :where [[:parent-post :== post-id] [:parent-comment :== parent-id]]
   :order-by [[:ts :desc] [:id :desc]] :doc-changes true})
(defn root-query [post-id amount]
  (assoc (thread-query post-id nil) :limit (inc amount)))

(def reveal-plan
  "Acquire two visible reply levels through the same shared query queue before
   child components mount. Explicit descendant folds still stop acquisition."
  [{:id :replies
    :queries (fn [{:keys [path]}] [(thread-query (first path) (last path))])}
   {:id :children :depends [:replies]
    :queries (fn [{:keys [path replies expanded]}]
               (mapv #(thread-query (first path) (:id %))
                     (filter #(and (or (nil? (:reply-count %)) (pos? (:reply-count %)))
                                   (expanded? expanded (conj path (:id %)))) replies)))}
   {:id :authors :depends [:replies :children]
    :queries (fn [{:keys [replies children]}]
               (mapv query/profile-query (sort (distinct (keep :user (concat replies children))))))}])

(defn reveal-duration-ms [count]
  ;; A short thread should finish quickly; large branches get a little more
  ;; room without accumulating a full transition at every nesting level.
  (+ 120 (* 20 (min 8 (max 1 count)))))
