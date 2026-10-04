(ns tolgraven.blog.subs
  (:require
    [tolgraven.react :as rf]
    [reagent.ratom :as ratom]
    [tolgraven.blog.model :as model]
    [clojure.string :as string]))

(rf/reg-sub :blog
  (fn [[_ path]]
    (case (first path)
      :posts (rf/subscribe [:<-store-q {:path-collection [:blog-posts]
                                        :scoped? true :summary? true}])
      :comments (rf/subscribe [:<-store-2 :blog-comments])
      (rf/subscribe [:get :blog])))
  (fn [data [_ path]]
    (get-in data (if (#{:posts :comments} (first path)) (rest path) path))))

(rf/reg-sub :blog/post-feed
  :<- [:blog [:posts]]
  (fn [posts _]
    (some->> posts
             vals
             (sort-by :ts)
             reverse)))

(rf/reg-sub :blog/post-ids
  :<- [:blog [:posts]]
  (fn [posts _]
    (->> posts
         vals
         (keep :id)
         sort
         reverse)))

(rf/reg-sub :blog/post-records
  (fn [[_ post-id]]
    (rf/subscribe [:<-store-q {:path-collection [:blog-posts]
                               :scoped? true
                               :where [[:id :== post-id]]
                               :doc-changes true}]))
  (fn [posts _] posts))

(rf/reg-sub :blog/post
  (fn [[_ post-id]] (rf/subscribe [:blog/post-records post-id]))
  (fn [posts [_ post-id]]
    (some #(when (= post-id (:id %)) %) (vals posts))))

(rf/reg-sub :blog/post-summary
  :<- [:blog [:posts]]
  (fn [posts [_ id]] (some #(when (= id (:id %)) %) (vals posts))))

(rf/reg-sub :blog/permalink-for-path
  (fn [[_ path]]
    (rf/subscribe [:href :blog-post {:permalink path}]))
  (fn [link _]
    link))

(rf/reg-sub :blog/post-preview
  (fn [[_ id]]
    [(rf/subscribe [:blog/post id])
     (rf/subscribe [:blog/permalink-for-path id])])
  (fn [[post link] _]
    (let [preview (some->> (:text post)
                           string/split-lines
                           (filter #(not (string/blank? %)))
                           (take 2)
                           (string/join "  \n"))]
      (str preview
           (when (> (count (:text post)) (count preview))
             (str "    \n[...](" link ")"))))))

(rf/reg-sub :blog/post-tags
  (fn [[_ id]] (rf/subscribe [:blog/post id]))
  (fn [post _] (set (model/tags (:tags post)))))

(rf/reg-sub :blog/posts-with-tag
  :<- [:blog/post-feed]
  (fn [posts [_ tag]]
    (filter #(contains? (set (model/tags (:tags %))) tag) posts)))

(rf/reg-sub :blog/all-tags
  :<- [:blog/post-feed]
  (fn [posts _]
    (into #{} (mapcat #(model/tags (:tags %))) posts)))

(rf/reg-sub :blog/state
  :<- [:state [:blog]]
  (fn [state [_ path]]
    (get-in state path)))

(rf/reg-sub :blog/nav-page
  :<- [:blog/state [:page]]
  (fn [page _]
    (if (and (int? page) (<= 0 page)) page 0)))

(rf/reg-sub :blog/page-index-for-nav-action
  :<- [:blog/nav-page]
  :<- [:blog/posts-per-page]
  :<- [:blog/count]
  (fn [[index size total] [_ action]]
    ;; Return one-based route numbers, not app-db indices.
    (case action
      :prev (when (pos? index) index)
      :next (when (< (* size (inc index)) total) (+ index 2))
      nil)))

(rf/reg-sub :blog/count
  :<- [:blog [:posts]]
  (fn [posts _]
    (count posts)))

(rf/reg-sub :blog/posts-per-page
  :<- [:option [:blog]]
  (fn [options _]
    (model/page-size (:posts-per-page options))))

(rf/reg-sub :blog/adjacent-post-id
  :<- [:blog/post-ids]
  (fn [post-ids [_ direction current-id]]
    (let [[before current-and-after] (split-with #(not= % current-id) post-ids)]
      (when (seq current-and-after)
        (case direction
          :prev (last before)
          :next (second current-and-after)
          nil)))))

(rf/reg-sub :blog/ids-for-page
  :<- [:blog/post-ids]
  (fn [ids [_ index size]] (model/page-ids ids index size)))

(rf/reg-sub :comments/all ; legacy cached lookup
  :<- [:<-store-2 :blog-comments]
  (fn [comments _]
    comments))

;; Legacy cached lookups remain available alongside query-backed subscriptions.
(rf/reg-sub :comments/for-user
  (fn [[_ user-id]] (rf/subscribe [:comments/all]))
  (fn [comments [_ user-id]] (filter #(= user-id (:user %)) (vals comments))))

(rf/reg-sub :comments/for-user-q
  (fn [[_ user-id]]
    (rf/subscribe [:<-store-q {:path-collection [:blog-comments]
                              :scoped? true
                              :where [[:user :== user-id]]
                              :order-by [[:ts :desc]]
                              :doc-changes true}]))
  (fn [comments _] comments))

(rf/reg-sub :comments/count-for-user
  (fn [[_ user-id]] (rf/subscribe [:comments/for-user-q user-id]))
  (fn [comments _] (count comments)))

(rf/reg-sub :comments/for-post
  (fn [[_ blog-id]]
    [(rf/subscribe [:comments/all])
     (rf/subscribe [:blog/post blog-id])])
  (fn [[comments post] _]
    (let [comment-ids (if (sequential? (:comments post)) ; support old style (store )
                        (:comments post)
                        (keys (:comments post)))]
      (vals (select-keys comments comment-ids)))))

(rf/reg-sub :comments/for-post-q
  (fn [[_ blog-id]] (rf/subscribe [:blog/post blog-id]))
  (fn [post _] (:comments post)))

(rf/reg-sub :comments/thread-records
  (fn [[_ blog-id parent-id]]
    (rf/subscribe [:<-store-q {:path-collection [:blog-comments]
                              :scoped? true
                              :where [[:parent-post :== blog-id]
                                      [:parent-comment :== parent-id]]
                              :order-by [[:ts :desc]]
                              :doc-changes true}]))
  (fn [comments _] comments))

(rf/reg-sub :comments/for-q-flat
  (fn [[_ blog-id parent-id]] (rf/subscribe [:comments/thread-records blog-id parent-id]))
  (fn [comments _] (when (seq comments) comments)))

;; Reserved for direct comment lookup; keep the unfinished subscription visible.
(rf/reg-sub :comments/for-id
  (fn [db [_ comment-id]]))

(rf/reg-sub :comments/thread-expanded?
  :<- [:blog/state [:comment-thread-expanded]]
  (fn [expanded [_ path]]
    ;; Root comments start expanded; explicit false always wins.
    (if-some [value (get expanded path)] value (= 2 (count path)))))

(rf/reg-sub :comments/adding?
  :<- [:blog/state [:adding-comment]]
  (fn [adding [_ path]]
    (get adding path))) ; entire path is the key, hence get not get-in

(rf/reg-sub :blog/vote
  (fn [db [_ path]]
    (case (get-in db [:state :active-user :comment-votes (keyword (str (last path)))] 0)
      1 :up -1 :down nil)))

(rf/reg-sub-raw :blog/page-ready?
  (fn [_ [_ {:keys [page post-id]}]]
    (ratom/make-reaction
     (fn []
       (let [posts @(rf/subscribe [:blog [:posts]])
             size @(rf/subscribe [:blog/posts-per-page])
             ids (if post-id [post-id]
                     @(rf/subscribe [:blog/ids-for-page (dec (or page 1)) size]))
             ;; Realize every input so bodies and comment threads start together.
             ready (mapv (fn [id]
                           [(some? @(rf/subscribe [:blog/post-records id]))
                            (some? @(rf/subscribe [:comments/thread-records id]))]) ids)]
         (and (or post-id (some? posts)) (every? true? (mapcat identity ready))))))))

(rf/reg-sub :blog/post-loaded?
  (fn [[_ id]] (rf/subscribe [:blog/post-records id]))
  (fn [records _] (some? records)))
