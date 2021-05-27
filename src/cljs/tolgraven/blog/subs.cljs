(ns tolgraven.blog.subs
  (:require
    [re-frame.core :as rf]))


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

(rf/reg-sub :blog/state
 :<- [:state [:blog]]
 (fn [state [_ path]]
   (get-in state path)))

(rf/reg-sub :blog/nav-page
 :<- [:blog/state [:page]]
 (fn [page [_ _]]
   (or page 0)))

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
   (vals (select-keys comments (get post :comments)))))

(rf/reg-sub :comments/for-id
 (fn [db [_ comment-id]]))
