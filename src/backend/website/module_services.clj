(ns website.module-services
  "Selected modules own background services and return their lifecycle cleanup."
  (:require [mount.core :as mount]
            [website.config :as config]
            [website.content.service :as content]
            [website.site-catalog :as catalog]))

(defn start! [declarations]
  (loop [entries (seq (sort-by key declarations)) cleanups []]
    (if-let [[_ {:keys [start]}] (first entries)]
      (let [cleanup (try (when start ((requiring-resolve start)))
                         (catch Exception error
                           (doseq [stop! (reverse cleanups)] (stop!))
                           (throw error)))]
        (recur (next entries) (cond-> cleanups cleanup (conj cleanup))))
      cleanups)))

(mount/defstate running
  :start (do config/env content/startup-content (start! catalog/modules))
  :stop (doseq [stop! (reverse running)] (stop!)))
