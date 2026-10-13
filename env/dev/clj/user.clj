(ns user
  "Userspace functions you can run by default in your local REPL."
  (:require
   [tolgraven.config :refer [env]]
   [tolgraven.routes.home :as home]
   [clojure.pprint]
   [clojure.java.shell :as shell]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [expound.alpha :as expound]
   [mount.core :as mount]
   [tolgraven.dev.shadow :as shadow-startup]
   [tolgraven.handler :as handler]
   [tolgraven.ssr :as ssr]
   [tolgraven.core]))


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

(defn- ensure-styles! []
  (let [{:keys [exit out err]} (shell/sh "node" "scripts/build/styles.mjs" "--if-stale")]
    (when-not (zero? exit)
      (throw (ex-info "Development styles failed to build. Run npm run build before starting HTTP."
                      {:exit exit, :error err})))
    (print out)))

(defn start "Starts application.  You'll usually want to run this on startup." []
  ;; A new SSR/browser build must never paint against CSS from an older checkout.
  ;; Fresh styles skip Sass/PostCSS; Shadow still starts on its deferred thread.
  (ensure-styles!)
  (shadow-startup/prepare!)
  (home/gen-sitemap!)
  (doseq [component (-> (mount/start-without #'tolgraven.core/repl-server)
                        :started)]
    (log/info component "started"))
  (alter-var-root #'s/*explain-out* (constantly expound/printer))
  (add-tap (bound-fn* clojure.pprint/pprint)) ; (tap> ) goes to repl, anything else?
  (shadow-startup/start! (cljs-builds :app-dev))
  nil)

(defn stop "Stops application." []
  (doseq [component (-> (mount/stop-except #'tolgraven.core/repl-server)
                        :stopped)]
    (log/info component "stopped")))

(defn restart "Restarts application." []
  (stop)
  (start))

(defn restart-handler []
  (mount/stop #'handler/app-routes)
  (mount/start #'handler/app-routes))
; (restart-handler)
