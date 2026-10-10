(ns tolgraven.modules.instagram.subs
  (:require
    [tolgraven.react :as rf]))

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
            vals
            (sort-by #(let [ms (js/Date.parse (:timestamp %))]
                        (if (js/Number.isFinite ms) ms 0)) >)
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
