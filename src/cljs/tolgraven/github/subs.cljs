(ns tolgraven.github.subs
  (:require [re-frame.core :as rf]
            [clojure.string :as string]
            [cljs-time.coerce :as ctc]
            [cljs-time.core :as ct]))

(rf/reg-sub :github/commits
 :<- [:content [:github]]
 (fn [github]
   (:repo github)))

(rf/reg-sub :github/commit-count
 :<- [:content [:github :repo-headers]]
 (fn [headers]
   (if headers
     (-> headers
         :link
         (string/split "page=") last
         (string/split ">")     first
         js/parseInt)
     "Loading")))

(rf/reg-sub :github/commit
 :<- [:content [:github]]
 (fn [github [_ sha]]
   (get-in github [:commit sha])))
