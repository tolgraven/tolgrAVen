(ns website.modules.github.subs
  (:require [website.react :as r]))

(r/reg-sub :github/configuration
 (fn [db _]
   (get-in db [:module :github :configuration :settings])))

(r/reg-sub :github/get-from
 :<- [:github/configuration]
 (fn [{:keys [user repo]}]
   [user repo]))

(r/reg-sub :github/website-url
 :<- [:github/get-from]
 (fn [[user repo] _]
   (when (and (seq user) (seq repo))
     (str "https://github.com/" user "/" repo))))

(r/reg-sub :github/commits
 :<- [:content [:github]]
 (fn [github]
   (:commits github)))

(r/reg-sub :github/commit-count
 :<- [:content [:github :total]]
 (fn [total _] (if (some? total) total "Loading")))

(r/reg-sub :github/archive-complete?
 :<- [:content [:github :complete?]]
 (fn [complete? _] (true? complete?)))

(r/reg-sub :github/more?
 :<- [:content [:github :next-cursor]]
 (fn [cursor _] (some? cursor)))

(r/reg-sub :github/commit
 :<- [:content [:github]]
 (fn [github [_ sha]]
   (get-in github [:commit sha])))
