(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require
   [tolgraven.config :refer [env]]
    [clojure.pprint]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [expound.alpha :as expound]
    [mount.core :as mount]
    [tolgraven.figwheel :refer [start-fw stop-fw cljs]]
    [tolgraven.core :refer [start-app]]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint))

(defn start "Starts application.  You'll usually want to run this on startup." []
  (doseq [component (-> (mount/start-without #'tolgraven.core/repl-server)
                        :started)]
    (log/info component "started"))
  (start-fw))

(defn stop "Stops application." []
  (doseq [component (-> (mount/stop-except #'tolgraven.core/repl-server)
                        :stopped)]
    (log/info component "stopped")))

(defn restart "Restarts application." []
  (stop)
  (start))

