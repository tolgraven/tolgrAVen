(ns website.modules.blog.subs
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require #?(:clj [website.build.site :refer [when-enabled]])
    [website.react :as r]

    [website.modules.blog.model :as model]
    [website.modules.blog.schema :as schema]
    [website.schema.common :as c]
    #?(:feature/blog-comments [website.modules.blog.comments :as comments])
    [website.modules.blog.data :as data]
    [website.supabase.scoped :as scoped]
    [website.util :as util]
    [clojure.string :as string] )))

#?(:cljs
   (do
(r/reg-sub :blog
  (fn [[_ path]]
    (case (first path)
      :posts (r/subscribe [:<-store-q data/summaries-query])
      :comments (r/subscribe [:<-store-2 :blog-comments])
      (r/subscribe [:get :blog])))
  (fn [data [_ path]]
    (get-in data (if (#{:posts :comments} (first path)) (rest path) path))))

(r/reg-sub :blog/post-feed
  {:args schema/no-args, :result schema/post-list}
  :<- [:blog [:posts]]
  (fn [posts _]
    (some->> posts
             vals
             (sort-by :ts)
             reverse)))

(r/reg-sub :blog/post-ids
  {:args schema/no-args, :result [:sequential c/id]}
  :<- [:blog [:posts]]
  (fn [posts _]
    (->> posts
         vals
         (keep :id)
         sort
         reverse)))

(r/reg-sub :blog/post-records
  {:args schema/selected-id-args, :result schema/post-records}
  (fn [[_ post-id]]
    ;; A selection can be absent before routing or in an empty SSR snapshot.
    ;; Keep a reactive empty value without acquiring a query for a nil ID.
    (r/subscribe (if (some? post-id) [:<-store-q (data/post-query post-id)] [:nil])))
  (fn [posts _] posts))

(r/reg-sub :blog/post
  {:args schema/selected-id-args, :result [:maybe schema/displayed-post]}
  (fn [[_ post-id]] (r/subscribe [:blog/post-records post-id]))
  (fn [posts [_ post-id]]
    (some #(when (= post-id (:id %)) %) (vals posts))))

(r/reg-sub :blog/post-summary
  {:args schema/id-args, :result [:maybe schema/displayed-post]}
  :<- [:blog [:posts]]
  (fn [posts [_ id]] (some #(when (= id (:id %)) %) (vals posts))))

(r/reg-sub :blog/permalink-for-path
  (fn [[_ path]]
    (r/subscribe [:href :blog-post {:permalink path}]))
  (fn [link _]
    link))

(r/reg-sub :blog/post-preview
  {:args schema/id-args, :result :string}
  (fn [[_ id]]
    [(r/subscribe [:blog/post id])
     (r/subscribe [:blog/permalink-for-path id])])
  (fn [[post link] _]
    (let [preview (some->> (:text post)
                           string/split-lines
                           (filter #(not (string/blank? %)))
                           (take 2)
                           (string/join "  \n"))]
      (str preview
           (when (> (count (:text post)) (count preview))
             (str "    \n[...](" link ")"))))))

(r/reg-sub :blog/post-tags
  {:args schema/id-args, :result [:set :string]}
  (fn [[_ id]] (r/subscribe [:blog/post id]))
  (fn [post _] (set (model/tags (:tags post)))))

(r/reg-sub :blog/posts-with-tag
  {:args [:tuple :string], :result schema/post-list}
  (fn [[_ tag]] (r/subscribe [:<-store-q (data/tag-query tag)]))
  (fn [posts _] (when (some? posts) (sort-by :id > (vals posts)))))

(r/reg-sub :blog/posts-for-page
  {:args schema/page-args, :result schema/post-list}
  (fn [[_ index size]] (r/subscribe [:<-store-q (data/page-query index size)]))
  (fn [posts _] (when (some? posts) (sort-by :id > (vals posts)))))

(r/reg-sub :blog/all-tags
  {:args schema/no-args, :result [:set :string]}
  :<- [:blog/post-feed]
  (fn [posts _]
    (into #{} (mapcat #(model/tags (:tags %))) posts)))

(r/reg-sub :blog/state
  :<- [:state [:blog]]
  (fn [state [_ path]]
    (get-in state path)))

(r/reg-sub :blog/nav-page
  {:args schema/no-args, :result c/nonnegative}
  :<- [:blog/state [:page]]
  (fn [page _]
    (if (and (int? page) (<= 0 page)) page 0)))

(r/reg-sub :blog/page-index-for-nav-action
  :<- [:blog/nav-page]
  :<- [:blog/posts-per-page]
  :<- [:blog/count]
  (fn [[index size total] [_ action]]
    ;; Return one-based route numbers, not app-db indices.
    (case action
      :prev (when (pos? index) index)
      :next (when (< (* size (inc index)) total) (+ index 2))
      nil)))

(r/reg-sub :blog/count
  {:args schema/no-args, :result c/nonnegative}
  :<- [:blog [:posts]]
  (fn [posts _]
    (count posts)))

(r/reg-sub :blog/posts-per-page
  {:args schema/no-args, :result c/positive}
  :<- [:option [:blog]]
  (fn [options _]
    (model/page-size (:posts-per-page options))))

(r/reg-sub :blog/adjacent-post-id
  {:args [:tuple [:enum :prev :next] c/id], :result [:maybe c/id]}
  :<- [:blog/post-ids]
  (fn [post-ids [_ direction current-id]]
    (let [[before current-and-after] (split-with #(not= % current-id) post-ids)]
      (when (seq current-and-after)
        (case direction
          :prev (last before)
          :next (second current-and-after)
          nil)))))

(r/reg-sub :blog/ids-for-page
  {:args schema/page-args, :result [:?sequential c/id]}
  :<- [:blog/post-ids]
  (fn [ids [_ index size]] (model/page-ids ids index size)))

(when-enabled :blog :comments
  (r/reg-sub :comments/all
  {:args schema/no-args, :result schema/comment-records} ; legacy cached lookup
  :<- [:<-store-2 :blog-comments]
  (fn [comments _]
    comments)))

;; Legacy cached lookups remain available alongside query-backed subscriptions.
(when-enabled :blog :comments
  (r/reg-sub :comments/for-user
  (fn [[_ user-id]] (r/subscribe [:comments/all]))
  (fn [comments [_ user-id]] (filter #(= user-id (:user %)) (vals comments)))))

(when-enabled :blog :comments
  (r/reg-sub :comments/for-user-q
  {:args schema/id-args, :result schema/comment-records}
  (fn [[_ user-id]]
    (r/subscribe [:<-store-q {:path-collection [:blog-comments]
                              :scoped? true
                              :where [[:user :== user-id]]
                              :order-by [[:ts :desc]]
                              :doc-changes true}]))
  (fn [comments _] comments)))

(when-enabled :blog :comments
  (r/reg-sub :comments/count-for-user
  {:args schema/id-args, :result c/nonnegative}
  (fn [[_ user-id]] (r/subscribe [:comments/for-user-q user-id]))
  (fn [comments _] (count comments))))

(when-enabled :blog :comments
  (r/reg-sub :comments/for-post
  (fn [[_ blog-id]]
    [(r/subscribe [:comments/all])
     (r/subscribe [:blog/post blog-id])])
  (fn [[comments post] _]
    (let [comment-ids (if (sequential? (:comments post)) ; support old style (store )
                        (:comments post)
                        (keys (:comments post)))]
      (vals (select-keys comments comment-ids))))))

(when-enabled :blog :comments
  (r/reg-sub :comments/for-post-q
  (fn [[_ blog-id]] (r/subscribe [:blog/post blog-id]))
  (fn [post _] (:comments post))))

(when-enabled :blog :comments
  (r/reg-sub :comments/limit
  {:args schema/id-args, :result c/positive}
  :<- [:blog/state [:comment-limit]]
  (fn [limits [_ id]] (data/comment-limit limits id))))

(when-enabled :blog :comments
  (r/reg-sub-raw :comments/root-page
  {:args schema/selected-id-args, :result schema/root-page}
  (fn [_ [_ id]]
    (r/make-reaction
     (fn []
       (if (nil? id)
         ;; Component-derived skeletons have no selected post to acquire yet.
         {:records nil, :loading? true, :more? false}
         (let [amount @(r/subscribe [:comments/limit id])
               value @(r/subscribe [:<-store-q (comments/root-query id amount)])
               [cached-amount cached] (when-not value
                                        (some (fn [size]
                                                (when-let [result @(r/subscribe [:store/scoped (scoped/query-key (comments/root-query id size))])]
                                                  [size (util/normalize-store-result result)]))
                                              (reverse (range comments/page-size amount comments/page-size))))
               rows (->> (vals (or value cached)) (sort-by (juxt :ts :id)) reverse)
               ids (set (map :id (take (if value amount (or cached-amount 0)) rows)))]
           {:records (when (or value cached) (into {} (filter (fn [[_ row]] (ids (:id row)))) (or value cached)))
            :loading? (nil? value)
            :more? (or (nil? value) (> (count rows) amount))})))))))

(when-enabled :blog :comments
  (r/reg-sub :comments/thread-records
  {:args schema/thread-args, :result schema/comment-records}
  (fn [[_ blog-id parent-id]]
    (if parent-id
      (r/subscribe [:<-store-q (comments/thread-query blog-id parent-id)])
      (r/subscribe [:comments/root-page blog-id])))
  (fn [value [_ _ parent-id]] (if parent-id value (:records value)))))

;; Folded threads retain their cached children for the exit transition without
;; owning a remote reader. Each child removes its DOM after delayed visibility.
(when-enabled :blog :comments
  (r/reg-sub :comments/cached-thread
  {:args schema/thread-args, :result schema/comment-records}
  (fn [[_ post-id parent-id]]
    (r/subscribe [:store/scoped (scoped/query-key (comments/thread-query post-id parent-id))]))
  (fn [result _] (some-> result util/normalize-store-result))))

(when-enabled :blog :comments
  (r/reg-sub :comments/for-q-flat
  {:args schema/thread-args, :result schema/comment-records}
  (fn [[_ blog-id parent-id]] (r/subscribe [:comments/thread-records blog-id parent-id]))
  (fn [comments _] (when (seq comments) comments))))

(when-enabled :blog :comments
  (r/reg-sub-raw :comments/reveal-thread
  {:args schema/path-args, :result schema/comment-records}
  (fn [_ [_ path]]
    (r/make-reaction
      #(let [expanded @(r/subscribe [:blog/state [:comment-thread-expanded]])
             {:keys [ready? values]} @(r/subscribe [:store/plan comments/reveal-plan
                                                    {:path path :expanded expanded}])]
         (when ready? (into {} (map (juxt :id identity)) (:replies values))))))))

(when-enabled :blog :comments
  (r/reg-sub :comments/visible-thread
  (fn [[_ post-id parent-id acquire? path]]
    [(r/subscribe [:comments/cached-thread post-id parent-id])
     (r/subscribe (if acquire?
                      (if path [:comments/reveal-thread path]
                          [:comments/thread-records post-id parent-id])
                      [:nil]))])
  (fn [[cached live] _]
    ;; Acquiring/releasing a live reader must not temporarily remove cached
    ;; children. An authoritative empty map still replaces stale cached rows.
    (if (some? live) live cached))))

;; Reserved for direct comment lookup; keep the unfinished subscription visible.
(when-enabled :blog :comments
  (r/reg-sub :comments/for-id
  (fn [db [_ comment-id]])))

(when-enabled :blog :comments
  (r/reg-sub :comments/thread-expanded?
  {:args schema/path-args, :result :boolean}
  :<- [:blog/state [:comment-thread-expanded]]
  (fn [expanded [_ path]]
    (comments/expanded? expanded path))))

(when-enabled :blog :comments
  (r/reg-sub :comments/adding?
  {:args schema/path-args, :result :?boolean}
  :<- [:blog/state [:adding-comment]]
  (fn [adding [_ path]]
    (get adding path)))) ; entire path is the key, hence get not get-in

(r/reg-sub :blog/vote
  {:args schema/path-args, :result [:?enum :up :down]}
  (fn [db [_ path]]
    (case (get-in db [:state :active-user :comment-votes (keyword (str (last path)))] 0)
      1 :up -1 :down nil)))

(r/reg-sub-raw :blog/page-ready?
  (fn [_ [_ selection]]
    (r/make-reaction
     #(-> @(r/subscribe [:store/plan data/plan
                          (assoc selection
                                 :size @(r/subscribe [:blog/posts-per-page])
                                 :comment-limits @(r/subscribe [:blog/state [:comment-limit]])
                                 :thread-expanded @(r/subscribe [:blog/state [:comment-thread-expanded]]))])
          :ready?))))

(r/reg-sub :blog/post-loaded?
  {:args schema/id-args, :result :boolean}
  (fn [[_ id]] (r/subscribe [:blog/post-records id]))
  (fn [records _] (some? records)))
))
