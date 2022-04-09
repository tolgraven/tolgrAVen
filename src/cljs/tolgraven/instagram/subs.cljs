(ns tolgraven.instagram.subs
  (:require
    [re-frame.core :as rf]
    [cljs-time.coerce :as ctc]
    [cljs-time.core :as ct]))

(rf/reg-sub :instagram/data
 (fn [db [_ path]]
   (get-in db (into [:instagram] path))))

(rf/reg-sub :instagram/content
 :<- [:content [:instagram]]
 (fn [content [_ path]]
   (get-in content path)))

(rf/reg-sub :instagram/posts
 :<- [:instagram/content [:posts]]
 (fn [posts-map [_ amount]]
   (some->> posts-map
            (reduce (fn [m [k v]]
                      (assoc m (ctc/from-string (:timestamp v)) v))
                    (sorted-map-by ct/after?)) ; dumb but sort-by was giving me shit
            vals
            (take amount))))

(rf/reg-sub :instagram/posts-urls
 :<- [:instagram/posts]
 (fn [posts [_ amount page]]
   (->> posts
        (map :media_url)
        (take amount))))

(rf/reg-sub :instagram/prev-page-url
 :<- [:instagram [:paging]]
 (fn [paging [_]]
   (get paging :prev)))
(rf/reg-sub :instagram/next-page-url
 :<- [:instagram [:paging]]
 (fn [paging [_]]
   (get paging :next)))
