(ns tolgraven.blog.subs
  (:require
    [re-frame.core :as rf]
    [tolgraven.util :as util]
    [clojure.string :as string]))


(rf/reg-sub :blog ; should prob go straight to posts and comments same
 (fn [db [_ path] ]
   (get-in db (into [:blog] path))))

(rf/reg-sub :blog/post-feed
 :<- [:blog [:posts]]
 (fn [posts [_ path]]
  (some->> (keys posts) ;XXX temp, fetch more sanely (for learning not bc needed)
           sort
           reverse
           (map posts))))

(rf/reg-sub :blog/post
 :<- [:blog [:posts]]
 (fn [posts [_ id]]
  (get posts id)))

(rf/reg-sub :blog/post-tags
 (fn [[_ id]]
   (rf/subscribe [:blog/post id]))
 (fn [post [_ _]]
  (some-> (:tags post)
          (string/split " ")
          set)))

(rf/reg-sub :blog/posts-with-tag
 :<- [:blog [:posts]]
 (fn [posts [_ tag]]
  (vals
   (select-keys
    posts
    (-> (reduce (fn [m [k v]]
              (let [v (assoc v :tags (string/split (:tags v) " "))]
                (if (some #{tag} (:tags v))
                  (assoc m k v)
                  m)))
            {} posts)
        reverse
        keys)))))

(rf/reg-sub :blog/all-tags
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

(rf/reg-sub :blog/adjacent-post ; when move to not fetching all posts at boot, sub like this (get whole post not just id) will help auto prefetch wohoo
 :<- [:blog/post-feed]
 (fn [posts [_ direction current-id]]
   (let [[before current-and-after] (split-with #(> (:id %) current-id) posts)]
     (case direction
       :prev (last before)
       :next (second current-and-after)))))


(rf/reg-sub :blog/posts-for-page
 :<- [:blog/post-feed] ; obvs will be, figure out idx range and ask server (then cache all already delivered in db)
 (fn [posts [_ idx page-size]]
     (when (seq posts)
       (try
        (nth (partition-all page-size posts)
             idx)
        (catch js/Error _))))) ;usually throws on first load saying idx not a number...

(rf/reg-sub :comments/all
 :<- [:blog [:comments]]
 (fn [comments [_ _]]
   comments))

(rf/reg-sub :comments/for-user ; tho w firestore can at least query so do that hah
 (fn [[_ user-id]]
   [(rf/subscribe [:comments/all])
    (rf/subscribe [:user/user user-id])])
 (fn [[comments user] [_ user-id]]
   (vals (select-keys comments
                      (:comments user)))))

(rf/reg-sub :comments/for-post
 (fn [[_ blog-id]]
   [(rf/subscribe [:comments/all])
    (rf/subscribe [:blog/post blog-id])])
 (fn [[comments post] [_ _]]
   ; somehow recursively, urr.
   ; the blog has :comments contains ids as set?
   ; but no, cause children.
   ; so map from ids to nil or more map if children.
   ; or just fuck this and use a real database?
  (vals (select-keys comments (->> (:comments post)
                                    keys
                                    (map name)
                                    (map js/parseInt)))))) ;workaround for comments being keywords. should change to nr but maybe keep in place for compatibility


(rf/reg-sub :comments/for-comment ;replies. only works for top level, fix...
 (fn [[_ blog-id comment-id]]
   [(rf/subscribe [:comments/for-post blog-id])
    (rf/subscribe [:blog/post blog-id])])
 (fn [[comments post] [_ blog-id comment-id]]
   (filter #(= (:id %) comment-id) (:comments post))))

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
   (get-in adding path)))
