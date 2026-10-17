(ns website.dev.terminal
  "Terminal client adapter; application and Shadow startup remain owned by user/start."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl.ack :as ack]
            [nrepl.cmdline :as cmdline]
            [nrepl.server :as server]))

(defn client-command [host port]
  ["bb" "--config" "config/neorepl.edn" "-m" "neorepl.main" "repl"
   "--host" host "--port" (str port)])

(defn run-client! [host port]
  ;; Lein trampoline and inherited descriptors let JLine use the real terminal.
  (.waitFor (.start (.inheritIO (ProcessBuilder. ^java.util.List (client-command host port))))))

(defn options [args]
  (let [[mode & remaining] (if (#{":start" ":headless" ":connect"} (first args))
                            args
                            (cons ":start" args))
        remaining' (if (and (= mode ":connect") (= 1 (count remaining)))
                    (let [[host port] (str/split (first remaining) #":" 2)]
                      (if port [":host" host ":port" port] [":port" host]))
                    remaining)]
    (when (odd? (count remaining'))
      (throw (ex-info "Use lein repl [:start|:headless|:connect] [:host HOST] [:port PORT]. For other Lein options use lein repl-classic."
                      {:args args})))
    ;; Values are passed through; only option positions are translated.
    (let [translated (mapcat (fn [[key value]]
                              [(or ({":host" "--bind", ":port" "--port"} key)
                                   (throw (ex-info "Unknown REPL option" {:option key})))
                               value])
                            (partition 2 remaining'))
          [parsed] (cmdline/args->cli-options translated)]
      {:mode mode
       :options (merge {:bind (or (System/getenv "LEIN_REPL_HOST") "127.0.0.1")
                        :port (or (System/getenv "LEIN_REPL_PORT") 7000)
                        :ack (System/getenv "LEIN_REPL_ACK_PORT")
                        :middleware '[cider.nrepl/cider-middleware
                                      cider.piggieback/wrap-cljs-repl
                                      shadow.cljs.devtools.server.nrepl/middleware]}
                       parsed)})))

(defn start! []
  ((requiring-resolve 'user/start)))

(defn stop! []
  (when-let [stop (resolve 'user/stop)] (stop)))

(defn- port-file-cleanup! [file previous port]
  ;; Another process may have replaced discovery while this server was running.
  (when (and (.isFile file) (= (str port) (str/trim (slurp file))))
    (if (some? previous)
      (spit file previous)
      (io/delete-file file))))

(defn run! [{:keys [mode options]}]
  (if (= ":connect" mode)
    (run-client! (:bind options) (:port options))
    (let [file (io/file ".nrepl-port")
          previous (when (.isFile file) (slurp file))
          server-options (cmdline/server-opts options)
          repl-server (cmdline/start-server (dissoc server-options :ack-port))
          cleaned? (atom false)
          cleanup! (fn []
                     (when (compare-and-set! cleaned? false true)
                       (try
                         (stop!)
                         (finally
                           (server/stop-server repl-server)
                           (port-file-cleanup! file previous (:port repl-server))))))
          hook (Thread. ^Runnable cleanup! "development-repl-cleanup")]
      (.addShutdownHook (Runtime/getRuntime) hook)
      (try
        ;; Keep the existing startup, CSS barrier, CMS recovery and native watches.
        (start!)
        (spit file (:port repl-server))
        (when-let [ack-port (:ack-port server-options)]
          (ack/send-ack (:port repl-server) ack-port))
        (println (cmdline/server-started-message repl-server server-options))
        (flush)
        (if (= ":headless" mode)
          (do @(promise) 0)
          (do
            ;; Ctrl-C belongs to the client/evaluation, rather than this server.
            (cmdline/set-signal-handler! "INT" (constantly nil))
            (run-client! (:host repl-server) (:port repl-server))))
        (finally
          (cleanup!)
          (.removeShutdownHook (Runtime/getRuntime) hook))))))

(defn -main [& args]
  (System/exit
   (try
     (run! (options args))
     (catch Throwable error
       (binding [*out* *err*]
         (println "Development REPL failed:" (ex-message error))
         (.printStackTrace error))
       1))))
