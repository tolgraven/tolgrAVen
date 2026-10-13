(ns tolgraven.modules.github.subs
  (:require [clojure.string :as string]
            [tolgraven.react :as r]))

(r/reg-sub :github/website-url
  (fn [[_ _]]
    (r/subscribe [:github/commits])) ; dumb to rely on this tho since will re-run a bunch for same res.
  (fn [commits _]
    (first (string/split (:html_url (first commits)) #"/commit/"))))

(r/reg-sub :github/get-from
 :<- [:option [:github]]
 (fn [github]
   [(:user github)
    (:repo github)]))

(r/reg-sub :github/commits
 :<- [:content [:github]]
 (fn [github]
   (:commits github)))

(r/reg-sub :github/commit-count
 :<- [:content [:github :repo-headers]]
 (fn [headers]
   (if headers
     (-> headers
         :link
         (string/split "page=") last
         (string/split ">")     first
         js/parseInt)
     "Loading")))

(r/reg-sub :github/commit
 :<- [:content [:github]]
 (fn [github [_ sha]]
   (get-in github [:commit sha])))

(r/reg-sub :github/filter-by
 :<- [:github/commits]
 :<- [:form-field [:github :search]]
 (fn [[commits search] [_ override]]
   (let [search (or override search)]
     (cond->> commits
       (not (string/blank? search)) (filter #(re-find (re-pattern search)
                                                      (string/join (:message %))))))))

