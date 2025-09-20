(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require
   [tolgraven.config :refer [env]]
    [tolgraven.routes.home :as home]
    [clojure.pprint]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [expound.alpha :as expound]
    [mount.core :as mount]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.cljs.devtools.server :as server]
    [tolgraven.handler :as handler]
    [tolgraven.core :refer [start-app]]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint)) ; (tap> ) goes to repl, anything else?

(defn cljs-repl "Connects to a given build-id. Defaults to `:app`."
  ([]
   (cljs-repl :app-dev))
  ([build-id]
   (server/start!)
   (shadow/watch build-id)
   (shadow/nrepl-select build-id)))

(defn start "Starts application.  You'll usually want to run this on startup." []
  (home/gen-sitemap!)
  (doseq [component (-> (mount/start-without #'tolgraven.core/repl-server)
                        :started)]
    (log/info component "started"))
  (cljs-repl))

(defn stop "Stops application." []
  (doseq [component (-> (mount/stop-except #'tolgraven.core/repl-server)
                        :stopped)]
    (log/info component "stopped")))

(defn restart "Restarts application." []
  (stop)
  (start))

(defn reload-deps []
  (log/warn "Disabled")
  #_(require 'alembic.still)
  #_(alembic.still/load-project))

(defn restart-handler []
  (mount/stop #'handler/app-routes)
  (mount/start #'handler/app-routes))
; (restart-handler)
