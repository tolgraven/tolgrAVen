(ns website.modules.github.subs
  (:require [clojure.string :as string]
            [website.modules.github.model :as model]
            [website.react :as r]))

(r/reg-sub :github/get-from
 :<- [:content [:github-settings]]
 (fn [github]
   [(:user github) (:repo github)]))

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
 :<- [:content [:github :repo-headers]]
 :<- [:github/commits]
 (fn [[headers commits] _]
   (if headers
     (if-let [page (some->> (:link headers)
                           (re-find #"[?&]page=(\d+)>; rel=\"last\"") second)]
       (js/parseInt page)
       (count commits))
     "Loading")))

(r/reg-sub :github/commit
 :<- [:content [:github]]
 (fn [github [_ sha]]
   (get-in github [:commit sha])))

(r/reg-sub :github/filter-by
 :<- [:github/commits]
 :<- [:form-field [:github :search]]
 (fn [[commits search] [_ override]]
   (filterv #(model/matches? % (or override search)) commits)))

(r/reg-sub :github/route-target
  :<- [:common/route]
  (fn [match _] (model/route-target match)))

(r/reg-sub :github/commit-error
  :<- [:content [:github :commit-errors]]
  (fn [errors [_ sha]] (get errors sha)))

(r/reg-sub :github/commit-listed?
  :<- [:github/commits]
  (fn [commits [_ sha]]
    (boolean (and sha (some #(string/starts-with? (:sha %) sha) commits)))))

