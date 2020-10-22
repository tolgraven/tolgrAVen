(ns tolgraven.blog.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :blog/path-from-uuid
 :<- [:content [:blog]]
 (fn [blog [_ uuid]])
  ) ; deep search for uuid, get path like [:2 :4 :1] - inefficient tho...
; (rf/reg-sub :uuid/typeof)

(rf/reg-sub :blog/content ;terrible name, basically everything not posts. posts should have own category altogether anyways...
 :<- [:content [:blog]]
 (fn [content [_ path]]
   (get-in content path)))

(rf/reg-sub :blog/posts
 :<- [:blog/content [:posts]]
 (fn [posts [_ path]]
   (get-in posts path)) )

(rf/reg-sub :blog/state
 :<- [:state [:blog]]
 (fn [state [_ path]]
   (get-in state path)) )

(rf/reg-sub :blog/count
 :<- [:content [:blog]]
 (fn [blog [_ path]]
   (count (:posts blog))))

(rf/reg-sub :blog/posts-for-page
 :<- [:blog/posts] ; obvs will be, figure out idx range and ask server (then cache all already delivered in db)
 (fn [posts [_ idx page-size]]
     (when posts
       (nth (->> posts
               reverse
               (partition (min (count posts) page-size))) ;watch out with partition returning empty for small seqs...
          (or idx 0))))) ;usually throws on first load saying idx not a number...



