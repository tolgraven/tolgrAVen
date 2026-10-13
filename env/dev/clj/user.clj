(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require
   [website.config :refer [env]]
   [website.routes.home :as home]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [expound.alpha :as expound]
   [mount.core :as mount]
   [website.dev.shadow :as shadow-startup]
   [website.handler :as handler]
   [website.ssr :as ssr]
   [website.core]))


(defn- cljs-builds [build-id]
  (cond-> []
    (not= :return-worker build-id) (conj :return-worker)
    (and (ssr/enabled?) (not= :ssr build-id)) (conj :ssr)
    (not= :document build-id) (conj :document)
    true (conj build-id)))

(defn cljs-repl "Connect to a watched build. Defaults to `:app-dev`."
  ([] (cljs-repl :app-dev))
  ([build-id]
   (shadow-startup/select! (cljs-builds build-id) build-id)))

(defn start "Starts application.  You'll usually want to run this on startup." []
  (home/gen-sitemap!)
  (doseq [component (-> (mount/start-without #'website.core/repl-server)
                        :started)]
    (log/info component "started"))
  (alter-var-root #'s/*explain-out* (constantly expound/printer))
  (add-tap (bound-fn* clojure.pprint/pprint)) ; (tap> ) goes to repl, anything else?
  (shadow-startup/start! (cljs-builds :app-dev))
  nil)

(defn stop "Stops application." []
  (doseq [component (-> (mount/stop-except #'website.core/repl-server)
                        :stopped)]
    (log/info component "stopped")))

(defn restart "Restarts application." []
  (stop)
  (start))

(defn restart-handler []
  (mount/stop #'handler/app-routes)
  (mount/start #'handler/app-routes))
; (restart-handler)
