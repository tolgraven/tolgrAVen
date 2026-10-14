(ns website.core
  (:require
    [website.handler :as handler]
    [website.content.service :as content]
    [website.module-services :as module-services]
    [website.routes.home :as home]
    [website.nrepl :as nrepl]
    [luminus.http-server :as http]
    [website.config :refer [env]]
    [clojure.tools.cli :refer [parse-opts]]
    [taoensso.timbre :as timbre]
    [mount.core :as mount])
  (:gen-class))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :parse-fn #(Integer/parseInt %)]])

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (do
    content/startup-content
    module-services/running
    (http/start
     (-> env
        (update :io-threads #(or % (* 2 (.availableProcessors (Runtime/getRuntime))))) 
        (merge {:handler (handler/app)}
               {:port (env :port)
                :http2? true
                :async? true
                :websocket? true
                :buffer-size 64368}))))
  :stop
  (http/stop http-server))

(mount/defstate ^{:on-reload :noop} repl-server
  :start
  (when (env :nrepl-port)
    (nrepl/start {:bind (env :nrepl-bind)
                  :port (env :nrepl-port)}))
  :stop
  (when repl-server
    (nrepl/stop repl-server)))


(defn stop-app []
  (doseq [component (:stopped (mount/stop))]
    (timbre/info component "stopped"))
  (shutdown-agents))

(defn start-app [args]
  (home/gen-sitemap!)
  (doseq [component (-> args
                        (parse-opts cli-options)
                        mount/start-with-args
                        :started)]
    (timbre/info component "started"))
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop-app)))

(defn -main [& args]
  (start-app args))
