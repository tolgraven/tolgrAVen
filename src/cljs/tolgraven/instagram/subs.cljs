(ns tolgraven.instagram.subs
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub :instagram/data
 (fn [db [_ path]]
   (get-in db (into [:instagram] path))))

(rf/reg-sub :instagram/content
 :<- [:content [:instagram]]
 (fn [content [_ path]]
   (get-in content path)))

(rf/reg-sub :instagram/posts
 :<- [:instagram/content [:posts]]
 (fn [posts-map [_ id]]
   (->> posts-map
        (into (sorted-map))
        vals)))

(rf/reg-sub :instagram/posts-urls
 :<- [:instagram/posts]
 (fn [posts [_ amount page]]
   (->> posts
        (map :media_url)
        (take amount))))

(rf/reg-sub :instagram/prev-page-url
 :<- [:instagram [:paging]]
 (fn [paging [_]]
   (get-in paging :prev)))
(rf/reg-sub :instagram/next-page-url
 :<- [:instagram [:paging]]
 (fn [paging [_]]
   (get-in paging :next)))
