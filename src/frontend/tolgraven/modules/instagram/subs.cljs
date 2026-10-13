(ns tolgraven.modules.instagram.subs
  (:require
    [tolgraven.react :as r]))

(r/reg-sub :instagram/data
 (fn [db [_ path]]
   (get-in db (into [:instagram] path))))

(r/reg-sub :instagram/content
 :<- [:content [:instagram]]
 (fn [content [_ path]]
   (get-in content path)))

(r/reg-sub :instagram/posts
 :<- [:instagram/content [:posts]]
 (fn [posts-map [_ amount]]
   (some->> posts-map
            vals
            (sort-by #(let [ms (js/Date.parse (:timestamp %))]
                        (if (js/Number.isFinite ms) ms 0)) >)
            (take amount))))

(r/reg-sub :instagram/posts-urls
 :<- [:instagram/posts]
 (fn [posts [_ amount page]]
   (->> posts
        (map :media_url)
        (take amount))))

(r/reg-sub :instagram/prev-page-url
 :<- [:instagram [:paging]]
 (fn [paging [_]]
   (get paging :prev)))
(r/reg-sub :instagram/next-page-url
 :<- [:instagram [:paging]]
 (fn [paging [_]]
   (get paging :next)))
