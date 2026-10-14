(ns website.modules.github.archive
  "Read/search the complete stored history; page views never drive ingestion."
  (:require [clojure.string :as string]
            [website.modules.github.sync :as sync]
            [website.modules.github.archive-schema :as schema]
            [website.validation :as validation]
            [website.platform.supabase :as platform]))

(defn provider-commit [{:keys [sha message authored_at committed_at author_name author_login avatar_url html_url]}]
  {:sha sha
   :html_url html_url
   :author {:login author_login, :avatar_url avatar_url}
   :commit {:message message
            :author {:name author_name, :date authored_at}
            :committer {:date committed_at}}})

(defn cursor? [value]
  (try
    (let [[timestamp sha & extra] (when (and (string? value) (<= (count value) 100))
                                   (string/split value #"\|" -1))]
      (boolean (and (empty? extra) timestamp sha
                    (re-matches #"[a-fA-F0-9]{40,64}" sha)
                    (java.time.Instant/parse timestamp))))
    (catch Exception _ false)))

(defn response! [{:keys [user repo q cursor]}]
  (try
    (let [target (sync/target!)]
      (if-not (= target {:owner (string/lower-case user), :repo (string/lower-case repo)})
        {:status 404, :body {:error "Repository is not configured"}}
        (let [[before-time before-sha] (when cursor (string/split cursor #"\|" 2))
              value (:body (platform/request! :post "rpc/website_search_github"
                            {:content-type :json
                             :form-params {:p_owner (:owner target)
                                           :p_repo (:repo target)
                                           :p_query (or q "")
                                           :p_before_time before-time
                                           :p_before_sha before-sha}}))]
          {:status 200
           :headers {"Cache-Control" "public, max-age=30"}
           :body (validation/check! "GitHub archive response" schema/result
                   (update value :commits #(mapv provider-commit %)))})))
    (catch Exception _ {:status 503
                        :headers {"Cache-Control" "no-store"}
                        :body {:error "Commit archive is temporarily unavailable"}})))
