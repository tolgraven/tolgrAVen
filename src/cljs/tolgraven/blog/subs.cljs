(ns tolgraven.blog.subs
  (:require
    [re-frame.core :as rf]
    [tolgraven.util :as util]
    [clojure.string :as string]))


(rf/reg-sub :blog ; should prob go straight to posts and comments same
 (fn [db [_ path] ]
   (get-in db (into [:blog] path))))

(rf/reg-sub :blog/post-feed ; would likely return a reasonable amount of posts, then paged for more.
 :<- [:blog [:posts]]
 (fn [posts [_ path]]
   (some->> posts
            vals
            (sort-by :ts)
            reverse)))

(rf/reg-sub :blog/post-ids
 (fn [[_ _]]
   (rf/subscribe [:<-store-2 :blog-post-ids :id]))
 (fn [ids [_ path]]
   (->> (:id ids)
        keys
        (map name)
        (map js/Number)
        sort
        reverse)))

(rf/reg-sub :blog/get-new-post-id ; send this inced to post event... ditch the silly counters already lol
 (fn [[_ _]]
   (rf/subscribe [:blog/post-ids]))
 (fn [ids [_ path]]
   (inc (apply max ids))))

(rf/reg-sub :blog/post
  (fn [[_ post-id]]
    (rf/subscribe [:<-store-q {:path-collection [:blog-posts]
                               :where [[:id :== post-id] ]
                               :doc-changes true}]))          
  (fn [post [_ post-id]]
    (get post (keyword (str post-id)))))

(rf/reg-sub :blog/permalink-for-path
 (fn [[_ path]]
   (rf/subscribe [:href :blog-post {:permalink path}]))
 (fn [link _]
   link))

(rf/reg-sub :blog/post-preview
 (fn [[_ id]]
   [(rf/subscribe [:blog/post id])
    (rf/subscribe [:blog/permalink-for-path id])])
 (fn [[post link] [_ id]]
   (let [preview (some->> (:text post)
                          string/split-lines
                          (filter #(not (string/blank? %)))
                          (take 2)
                          (string/join "  \n"))]
     (str preview
          (when (> (count (:text post)) (count preview))
            (str "    \n[...](" link ")"))))))

(rf/reg-sub :blog/post-tags ; XXX should be an array already as stored...
 (fn [[_ id]]
   (rf/subscribe [:blog/post id]))
 (fn [post [_ _]]
  (some-> (:tags post)
          (string/split " ")
          set)))

(rf/reg-sub :blog/posts-with-tag
 :<- [:blog [:posts]]
 (fn [posts [_ tag]]
  (->
   (select-keys
    posts
    (-> (reduce (fn [m [k v]]
              (let [v (assoc v :tags (string/split (:tags v) " "))]
                (if (some #{tag} (:tags v))
                  (assoc m k v)
                  m)))
            {} posts)
        reverse
        keys))
   vals
   (->> (sort-by :ts)
        reverse))))


(rf/reg-sub :blog/all-tags ; TODO probably keep an array of them so dont have to trawl all posts to get all tags...
 :<- [:blog [:posts]]
 (fn [posts [_ ]]
  (->> (reduce (fn [s [k v]]
            (let [tags (when (pos? (count (:tags v)))
                         (string/split (:tags v) " "))]
              (if (some? (seq tags))
                (concat s tags)
                s)))
           #{} posts)
       set)))


(rf/reg-sub :blog/state
 :<- [:state [:blog]]
 (fn [state [_ path]]
   (get-in state path)))

(rf/reg-sub :blog/nav-page
 :<- [:blog/state [:page]]
 (fn [page [_ _]]
   (or page 0)))

(rf/reg-sub :blog/page-index-for-nav-action
 :<- [:blog/nav-page]
 :<- [:blog/posts-per-page]
 :<- [:blog/count]
 (fn [[curr-idx posts-per-page total] [_ action]]
   (case action
     :prev (when-not (= 0 curr-idx)
             (inc (dec curr-idx)))
     :next (when-not (<= total (* posts-per-page (inc curr-idx)))
                  (inc (inc curr-idx))))))

(rf/reg-sub :blog/count
 :<- [:blog [:posts]]
 (fn [posts [_ _]]
   (count posts)))

(rf/reg-sub :blog/posts-per-page
 :<- [:option [:blog]]
 (fn [options [_ ]]
   (get options :posts-per-page 1)))


(rf/reg-sub :blog/adjacent-post-id
 :<- [:blog/post-ids]
 (fn [post-ids [_ direction current-id]]
   (let [[before current-and-after] (split-with #(> % current-id) post-ids)]
     (case direction
       :prev (last before)
       :next (second current-and-after)))))


(rf/reg-sub :blog/ids-for-page
 :<- [:blog/post-ids] ; obvs will be, figure out idx range and ask server (then cache all already delivered in db)
 (fn [ids [_ idx page-size]]
     (when (seq ids)
       (try
        (nth (partition-all page-size ids)
             idx)
        (catch js/Error _))))) ;usually throws on first load saying idx not a number...


(rf/reg-sub :comments/all ;literally should not exist though
 :<- [:<-store-2 :blog-comments]
 (fn [comments [_ _]]
   comments))

;; XXX make subcollections for comments.
;; those can be grouped and searched so can stick to nested, no flat extra bs?
(rf/reg-sub :comments/for-user
 (fn [[_ user-id]]
   [(rf/subscribe [:comments/all])
    (rf/subscribe [:user/user user-id])])
 (fn [[comments user] [_ user-id]]
   (vals (select-keys comments
                      (map keyword (:comments user))))))

(rf/reg-sub :comments/for-user-q
 (fn [[_ user-id]]
   [(rf/subscribe [:<-store-q {:path-collection [:blog-comments]
                               :where [[:user :== user-id] ]
                               ; :limit 100 ; can then grab last item and use :last-visible. but would be tricky here...
                               :order-by [[:ts :desc]]
                               :doc-changes true }])])
 (fn [comments [_ user-id]]
   (first comments)))


(rf/reg-sub :comments/count-for-user
 (fn [[_ user-id]]
   [(rf/subscribe [:comments/for-user-q user-id])])
 (fn [[comments]]
   (count comments)))


(rf/reg-sub :comments/for-post
 (fn [[_ blog-id]]
   [(rf/subscribe [:comments/all])
    (rf/subscribe [:blog/post blog-id])])
 (fn [[comments post] [_ _]]
   (let [comment-ids (if (seq? (:comments post)) ; support old style (store )
                       (:comments post)
                       (keys (:comments post)))]
     (vals (select-keys comments comment-ids)))))

(rf/reg-sub :comments/for-post-q
 (fn [[_ blog-id]]
   [(rf/subscribe [:<-store-q {:path-collection [:blog-posts]
                               :where [[:id :== blog-id] ]
                               :order-by [[:ts :desc]] ; well will need publication ts, can just update though i guess
                               :doc-changes true }])])
 (fn [[post] [_ _]]
   (:comments post)))


(rf/reg-sub :comments/for-q-flat
 (fn [[_ blog-id maybe-parent-comment-id]]
   (let [where [[:parent-post :== blog-id]
                [:parent-comment :== maybe-parent-comment-id]]] ; nil hopefully works? NOPE wtf
     [(rf/subscribe [:<-store-q {:path-collection [:blog-comments]
                                 :where where
                                 :order-by [[:ts :desc]] ; well will need publication ts, can just update though i guess
                                 :doc-changes true}])]))
 (fn [[comments] [_ _]]
   (when (seq comments)
     comments)))

(rf/reg-sub :comments/for-id
 (fn [db [_ comment-id]]))

(rf/reg-sub :comments/thread-expanded?
 :<- [:blog/state [:comment-thread-expanded]] 
 (fn [expanded [_ path]]
   (or (get expanded path) ; actively expanded
       (and (= 2 (count path))  ; is second level comment (blog-post is first id) - meaning first two levels always shown. (2) yields replies to replies collapsing
            (nil? (get expanded path)))))) ; not yet un or collapsed...

(rf/reg-sub :comments/adding?
 :<- [:blog/state [:adding-comment]] 
 (fn [adding [_ path]]
   (get adding path))) ; entire path is the key, hence get not get-in
