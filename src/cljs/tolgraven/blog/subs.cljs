(ns tolgraven.blog.subs
  (:require
    [re-frame.core :as rf]))


; (rf/reg-sub :blog/path-from-uuid
;  :<- [:get :blog]
;  (fn [blog [_ uuid]])
;   ) ; deep search for uuid, get path like [:2 :4 :1] - inefficient tho...
; (rf/reg-sub :uuid/typeof)

; (rf/reg-sub :blog/content ;terrible name, basically everything not posts. posts should have own category altogether anyways...
;  :<- [:get [:blog]]
;  (fn [content [_ path]]
;    (get-in content path)))
(rf/reg-sub :blog
 :<- [:get :blog]
 (fn [blog [_ path] ]
   (get-in blog path)))

(rf/reg-sub :blog/posts
 :<- [:blog [:posts]]
 (fn [posts [_ path]]
  (some->> (keys posts) ;XXX temp, fetch more sanely (for learning not bc needed)
           sort
           reverse
           (map posts))))

(rf/reg-sub :blog/state
 :<- [:state [:blog]]
 (fn [state [_ path]]
   (get-in state path)))

(rf/reg-sub :blog/count
 :<- [:blog/posts]
 (fn [posts [_ path]]
   (count posts)))

(rf/reg-sub :blog/posts-per-page
 :<- [:option [:blog]]
 (fn [options [_ ]]
   (get options :posts-per-page 2)))


(rf/reg-sub :blog/posts-for-page
 :<- [:blog/posts] ; obvs will be, figure out idx range and ask server (then cache all already delivered in db)
 (fn [posts [_ idx page-size]]
  ; (some->> (keys posts)
  ;          sort
  ;          reverse
  ;          (map posts)
  ;          (partition-all page-size)
  ;          #(nth % idx))
  ; (some->> (keys posts)
  ;          sort
  ;          reverse
  ;          (map posts)
  ;          (partition-all page-size)
  ;          #(nth % idx))
     (when (seq posts)
       (try
        (nth (partition-all page-size posts)
             idx)
        (catch js/Error _))))) ;usually throws on first load saying idx not a number...

(rf/reg-sub :comments/for-user ; honestly just dumb compared to going directly to db impl
 :<- [:blog/posts]
 (fn [posts [_ user]]
   ))

(rf/reg-sub :comments/for-post
 (fn [db [_ blog-id]]))
