(ns tolgraven.modules.blog.subs
  (:require
    [tolgraven.react :as rf]
    [reagent.ratom :as ratom]
    [tolgraven.modules.blog.model :as model]
    [tolgraven.modules.blog.schema :as schema]
    [tolgraven.schema.common :as c]
    [tolgraven.modules.blog.comments :as comments]
    [tolgraven.modules.blog.data :as data]
    [tolgraven.supabase.scoped :as scoped]
    [tolgraven.util :as util]
    [clojure.string :as string]))

(rf/reg-sub :blog
  (fn [[_ path]]
    (case (first path)
      :posts (rf/subscribe [:<-store-q data/summaries-query])
      :comments (rf/subscribe [:<-store-2 :blog-comments])
      (rf/subscribe [:get :blog])))
  (fn [data [_ path]]
    (get-in data (if (#{:posts :comments} (first path)) (rest path) path))))

(rf/reg-sub :blog/post-feed
  {:args schema/no-args, :result schema/post-list}
  :<- [:blog [:posts]]
  (fn [posts _]
    (some->> posts
             vals
             (sort-by :ts)
             reverse)))

(rf/reg-sub :blog/post-ids
  {:args schema/no-args, :result [:sequential c/id]}
  :<- [:blog [:posts]]
  (fn [posts _]
    (->> posts
         vals
         (keep :id)
         sort
         reverse)))

(rf/reg-sub :blog/post-records
  {:args schema/selected-id-args, :result schema/post-records}
  (fn [[_ post-id]]
    ;; A selection can be absent before routing or in an empty SSR snapshot.
    ;; Keep a reactive empty value without acquiring a query for a nil ID.
    (rf/subscribe (if (some? post-id) [:<-store-q (data/post-query post-id)] [:nil])))
  (fn [posts _] posts))

(rf/reg-sub :blog/post
  {:args schema/selected-id-args, :result [:maybe schema/displayed-post]}
  (fn [[_ post-id]] (rf/subscribe [:blog/post-records post-id]))
  (fn [posts [_ post-id]]
    (some #(when (= post-id (:id %)) %) (vals posts))))

(rf/reg-sub :blog/post-summary
  {:args schema/id-args, :result [:maybe schema/displayed-post]}
  :<- [:blog [:posts]]
  (fn [posts [_ id]] (some #(when (= id (:id %)) %) (vals posts))))

(rf/reg-sub :blog/permalink-for-path
  (fn [[_ path]]
    (rf/subscribe [:href :blog-post {:permalink path}]))
  (fn [link _]
    link))

(rf/reg-sub :blog/post-preview
  {:args schema/id-args, :result :string}
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
  {:args schema/id-args, :result [:set :string]}
  (fn [[_ id]] (rf/subscribe [:blog/post id]))
  (fn [post _] (set (model/tags (:tags post)))))

(rf/reg-sub :blog/posts-with-tag
  {:args [:tuple :string], :result schema/post-list}
  (fn [[_ tag]] (rf/subscribe [:<-store-q (data/tag-query tag)]))
  (fn [posts _] (when (some? posts) (sort-by :id > (vals posts)))))

(rf/reg-sub :blog/posts-for-page
  {:args schema/page-args, :result schema/post-list}
  (fn [[_ index size]] (rf/subscribe [:<-store-q (data/page-query index size)]))
  (fn [posts _] (when (some? posts) (sort-by :id > (vals posts)))))

(rf/reg-sub :blog/all-tags
  {:args schema/no-args, :result [:set :string]}
  :<- [:blog/post-feed]
  (fn [posts _]
    (into #{} (mapcat #(model/tags (:tags %))) posts)))

(rf/reg-sub :blog/state
  :<- [:state [:blog]]
  (fn [state [_ path]]
    (get-in state path)))

(rf/reg-sub :blog/nav-page
  {:args schema/no-args, :result c/nonnegative}
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
  {:args schema/no-args, :result c/nonnegative}
  :<- [:blog [:posts]]
  (fn [posts _]
    (count posts)))

(rf/reg-sub :blog/posts-per-page
  {:args schema/no-args, :result c/positive}
  :<- [:option [:blog]]
  (fn [options _]
    (model/page-size (:posts-per-page options))))

(rf/reg-sub :blog/adjacent-post-id
  {:args [:tuple [:enum :prev :next] c/id], :result [:maybe c/id]}
  :<- [:blog/post-ids]
  (fn [post-ids [_ direction current-id]]
    (let [[before current-and-after] (split-with #(not= % current-id) post-ids)]
      (when (seq current-and-after)
        (case direction
          :prev (last before)
          :next (second current-and-after)
          nil)))))

(rf/reg-sub :blog/ids-for-page
  {:args schema/page-args, :result [:maybe [:sequential c/id]]}
  :<- [:blog/post-ids]
  (fn [ids [_ index size]] (model/page-ids ids index size)))

(rf/reg-sub :comments/all
  {:args schema/no-args, :result schema/comment-records} ; legacy cached lookup
  :<- [:<-store-2 :blog-comments]
  (fn [comments _]
    comments))

;; Legacy cached lookups remain available alongside query-backed subscriptions.
(rf/reg-sub :comments/for-user
  (fn [[_ user-id]] (rf/subscribe [:comments/all]))
  (fn [comments [_ user-id]] (filter #(= user-id (:user %)) (vals comments))))

(rf/reg-sub :comments/for-user-q
  {:args schema/id-args, :result schema/comment-records}
  (fn [[_ user-id]]
    (rf/subscribe [:<-store-q {:path-collection [:blog-comments]
                              :scoped? true
                              :where [[:user :== user-id]]
                              :order-by [[:ts :desc]]
                              :doc-changes true}]))
  (fn [comments _] comments))

(rf/reg-sub :comments/count-for-user
  {:args schema/id-args, :result c/nonnegative}
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

(rf/reg-sub :comments/limit
  {:args schema/id-args, :result c/positive}
  :<- [:blog/state [:comment-limit]]
  (fn [limits [_ id]] (data/comment-limit limits id)))

(rf/reg-sub-raw :comments/root-page
  {:args schema/selected-id-args, :result schema/root-page}
  (fn [_ [_ id]]
    (ratom/make-reaction
     (fn []
       (if (nil? id)
         ;; Component-derived skeletons have no selected post to acquire yet.
         {:records nil, :loading? true, :more? false}
         (let [amount @(rf/subscribe [:comments/limit id])
               value @(rf/subscribe [:<-store-q (comments/root-query id amount)])
               [cached-amount cached] (when-not value
                                        (some (fn [size]
                                                (when-let [result @(rf/subscribe [:store/scoped (scoped/query-key (comments/root-query id size))])]
                                                  [size (util/normalize-store-result result)]))
                                              (reverse (range comments/page-size amount comments/page-size))))
               rows (->> (vals (or value cached)) (sort-by (juxt :ts :id)) reverse)
               ids (set (map :id (take (if value amount (or cached-amount 0)) rows)))]
           {:records (when (or value cached) (into {} (filter (fn [[_ row]] (ids (:id row)))) (or value cached)))
            :loading? (nil? value)
            :more? (or (nil? value) (> (count rows) amount))}))))))

(rf/reg-sub :comments/thread-records
  {:args schema/thread-args, :result schema/comment-records}
  (fn [[_ blog-id parent-id]]
    (if parent-id
      (rf/subscribe [:<-store-q (comments/thread-query blog-id parent-id)])
      (rf/subscribe [:comments/root-page blog-id])))
  (fn [value [_ _ parent-id]] (if parent-id value (:records value))))

;; Folded threads retain their cached children for the exit transition without
;; owning a remote reader. Each child removes its DOM after delayed visibility.
(rf/reg-sub :comments/cached-thread
  {:args schema/thread-args, :result schema/comment-records}
  (fn [[_ post-id parent-id]]
    (rf/subscribe [:store/scoped (scoped/query-key (comments/thread-query post-id parent-id))]))
  (fn [result _] (some-> result util/normalize-store-result)))

(rf/reg-sub :comments/for-q-flat
  {:args schema/thread-args, :result schema/comment-records}
  (fn [[_ blog-id parent-id]] (rf/subscribe [:comments/thread-records blog-id parent-id]))
  (fn [comments _] (when (seq comments) comments)))

(rf/reg-sub-raw :comments/reveal-thread
  {:args schema/path-args, :result schema/comment-records}
  (fn [_ [_ path]]
    (ratom/make-reaction
      #(let [expanded @(rf/subscribe [:blog/state [:comment-thread-expanded]])
             {:keys [ready? values]} @(rf/subscribe [:store/plan comments/reveal-plan
                                                    {:path path :expanded expanded}])]
         (when ready? (into {} (map (juxt :id identity)) (:replies values)))))))

(rf/reg-sub :comments/visible-thread
  (fn [[_ post-id parent-id acquire? path]]
    [(rf/subscribe [:comments/cached-thread post-id parent-id])
     (rf/subscribe (if acquire?
                      (if path [:comments/reveal-thread path]
                          [:comments/thread-records post-id parent-id])
                      [:nil]))])
  (fn [[cached live] _]
    ;; Acquiring/releasing a live reader must not temporarily remove cached
    ;; children. An authoritative empty map still replaces stale cached rows.
    (if (some? live) live cached)))

;; Reserved for direct comment lookup; keep the unfinished subscription visible.
(rf/reg-sub :comments/for-id
  (fn [db [_ comment-id]]))

(rf/reg-sub :comments/thread-expanded?
  {:args schema/path-args, :result :boolean}
  :<- [:blog/state [:comment-thread-expanded]]
  (fn [expanded [_ path]]
    (comments/expanded? expanded path)))

(rf/reg-sub :comments/adding?
  {:args schema/path-args, :result [:maybe :boolean]}
  :<- [:blog/state [:adding-comment]]
  (fn [adding [_ path]]
    (get adding path))) ; entire path is the key, hence get not get-in

(rf/reg-sub :blog/vote
  {:args schema/path-args, :result [:maybe [:enum :up :down]]}
  (fn [db [_ path]]
    (case (get-in db [:state :active-user :comment-votes (keyword (str (last path)))] 0)
      1 :up -1 :down nil)))

(rf/reg-sub-raw :blog/page-ready?
  (fn [_ [_ selection]]
    (ratom/make-reaction
     #(-> @(rf/subscribe [:store/plan data/plan
                          (assoc selection
                                 :size @(rf/subscribe [:blog/posts-per-page])
                                 :comment-limits @(rf/subscribe [:blog/state [:comment-limit]])
                                 :thread-expanded @(rf/subscribe [:blog/state [:comment-thread-expanded]]))])
          :ready?))))

(rf/reg-sub :blog/post-loaded?
  {:args schema/id-args, :result :boolean}
  (fn [[_ id]] (rf/subscribe [:blog/post-records id]))
  (fn [records _] (some? records)))
