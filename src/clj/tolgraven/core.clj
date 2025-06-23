(ns tolgraven.core
  (:require
    [tolgraven.handler :as handler]
    [tolgraven.routes.home :as home]
    [tolgraven.nrepl :as nrepl]
    [luminus.http-server :as http]
    [tolgraven.config :refer [env]]
    [clojure.tools.cli :refer [parse-opts]]
    [taoensso.timbre :as timbre]
    [mount.core :as mount])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread ex]
      (timbre/error {:what :uncaught-exception
                     :exception ex
                     :where (str "Uncaught exception on" (.getName thread))}))))


(def cli-options
  [["-p" "--port PORT" "Port number"
    :parse-fn #(Integer/parseInt %)]])

(mount/defstate ^{:on-reload :noop} http-server
  :start
  (http/start
    (-> env
        (update :io-threads #(or % (* 2 (.availableProcessors (Runtime/getRuntime))))) 
        (merge {:handler (handler/app)}
               {:port (env :port)
                :http2? true
                :websocket? true
                :buffer-size 64368})))
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
