(ns website.modules.github.settings
  "One public selection for the managed widget binding and archive worker."
  (:require [website.config :as config]
            [website.content.contract :as content-contract]
            [website.content.service :as content]
            [website.modules.github.configuration-schema :as schema]
            [website.validation :as validation]))

(defn selection! []
  (let [explicit (select-keys (if (map? config/env) config/env {}) [:github-user :github-repo])
        selected (if (seq explicit)
                   {:user (:github-user explicit), :repo (:github-repo explicit)}
                   ;; A build may opt back into CMS ownership after publication.
                   ;; Do not probe an undeclared/unpublished CMS section.
                   (when (some #{:github-settings} content-contract/sections)
                     (get-in (content/bundle! [:github-settings]) [:content :github-settings])))]
    (when selected (validation/check! "GitHub repository settings" schema/selection selected))))

(defn response []
  (try
    {:status 200
     :headers {"Cache-Control" "public, max-age=120"}
     :body {:settings (selection!)}}
    (catch Exception _
      {:status 503
       :headers {"Cache-Control" "no-store"}
       :body {:error "GitHub settings are temporarily unavailable"}})))
