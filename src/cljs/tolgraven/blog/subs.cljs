(ns tolgraven.blog.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub
 :blog/path-from-uuid
 :<- [:content [:blog]]
 (fn [blog [_ uuid]])
  ) ; deep search for uuid, get path like [:2 :4 :1] - inefficient tho...
; (rf/reg-sub :uuid/typeof)

(rf/reg-sub :blog/content ;terrible name, basically everything not posts. posts should have own category altogether anyways...
 :<- [:content [:blog]]
 (fn [content [_ path]]
   (get-in content path)))

(rf/reg-sub :blog/posts
 :<- [:content [:blog]]
 (fn [blog [_ path]]
   (get-in (:posts blog) path)) )

(rf/reg-sub :blog/count
 :<- [:content [:blog]]
 (fn [blog [_ path]]
   (count (:posts blog))))

(rf/reg-sub :blog/posts-for-page
 :<- [:content [:blog :posts]]
 (fn [posts [_ idx page-size]]
   (nth (->> posts
             reverse
             (partition page-size))
        idx)))


(rf/reg-sub :blog-comment-input
 :<- [:state]
 (fn [state [_ id]]
   (or (->> state :blog (nth id) :comments :input) "")))


