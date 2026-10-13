(ns tolgraven.modules.github.subs
  (:require [clojure.string :as string]
            [tolgraven.react :as rf]))

(rf/reg-sub :github/get-from
 :<- [:content [:github-settings]]
 (fn [github]
   [(:user github) (:repo github)]))

(rf/reg-sub :github/website-url
 :<- [:github/get-from]
 (fn [[user repo] _]
   (when (and (seq user) (seq repo))
     (str "https://github.com/" user "/" repo))))

(rf/reg-sub :github/commits
 :<- [:content [:github]]
 (fn [github]
   (:commits github)))

(rf/reg-sub :github/commit-count
 :<- [:content [:github :repo-headers]]
 :<- [:github/commits]
 (fn [[headers commits] _]
   (if headers
     (if-let [page (some->> (:link headers)
                           (re-find #"[?&]page=(\d+)>; rel=\"last\"") second)]
       (js/parseInt page)
       (count commits))
     "Loading")))

(rf/reg-sub :github/commit
 :<- [:content [:github]]
 (fn [github [_ sha]]
   (get-in github [:commit sha])))

(rf/reg-sub :github/filter-by
 :<- [:github/commits]
 :<- [:form-field [:github :search]]
 (fn [[commits search] [_ override]]
   (let [search (or override search)]
     (cond->> commits
       (not (string/blank? search)) (filter #(re-find (re-pattern search)
                                                      (string/join (:message %))))))))

