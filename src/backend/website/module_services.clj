(ns website.module-services
  "Selected modules own background services and return their lifecycle cleanup."
  (:require [clojure.tools.logging :as log]
            [mount.core :as mount]
            [website.config :as config]
            [website.content.service :as content]
            [website.site-catalog :as catalog]))

(defn start! [declarations]
  (loop [entries (seq (sort-by key declarations)) cleanups []]
    (if-let [[id {:keys [start]}] (first entries)]
      (let [cleanup (try (when start ((requiring-resolve start)))
                         (catch Exception _
                           ;; Optional service configuration is module-owned.
                           ;; Never tear down healthy siblings or abort HTTP.
                           (log/warn "Module service unavailable" (name id))
                           nil))]
        (recur (next entries) (cond-> cleanups cleanup (conj cleanup))))
      cleanups)))

(mount/defstate running
  :start (do config/env content/startup-content (start! catalog/modules))
  :stop (doseq [stop! (reverse running)] (stop!)))
