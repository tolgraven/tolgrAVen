(ns website.modules.github.search
  "Search scope follows the configured repository and its current visibility."
  (:require [website.modules.github.sync :as sync]
            [website.modules.github.provider :as provider]
            [website.platform.supabase :as platform]))

(defn filter! []
  (when-let [{:keys [owner repo]} (sync/target!)]
    (provider/repository-path owner repo)
    (let [rows (:body (platform/request! :get "github_repositories"
                       {:query-params {:select "owner,repo,is_public"
                                       :owner (str "eq." owner)
                                       :repo (str "eq." repo)
                                       :limit 1}}))]
      (when (true? (:is_public (first rows)))
        (str "repository:=`" owner "/" repo "`")))))
