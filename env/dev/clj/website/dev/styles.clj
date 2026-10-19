(ns website.dev.styles
  "Owned CSS watcher and fail-closed development freshness boundary."
  (:require [cheshire.core :as json]
            [clojure.java.shell :as shell]))

(defonce *watcher (atom nil))

(defn ensure-current! []
  (locking *watcher
    (let [{:keys [exit out err]} (shell/sh "node" "scripts/build/styles.mjs" "--if-stale")]
      (when-not (zero? exit)
        (throw (ex-info "Development styles failed to build. Fix Sass or run npm run build."
                        {:status 503, :exit exit, :error err})))
      (when-not (.startsWith ^String out "CSS is current") (print out))
      (:revision (json/parse-string (slurp "resources/public/css/website/.build-revision.json") true)))))

(defn stop! []
  (locking *watcher
    (when-let [process @*watcher]
      (.destroy ^Process process)
      (when-not (.waitFor ^Process process 2 java.util.concurrent.TimeUnit/SECONDS)
        (.destroyForcibly ^Process process))
      (reset! *watcher nil))))

(defonce shutdown-hook
  (let [hook (Thread. ^Runnable stop! "development-css-stop")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn start! []
  (locking *watcher
    (let [revision (ensure-current!)]
      (when-not (and @*watcher (.isAlive ^Process @*watcher))
        (reset! *watcher (-> (ProcessBuilder. ^java.util.List ["node" "scripts/build/styles.mjs" "--watch"])
                             (.inheritIO)
                             (.start))))
      revision)))

(defn before-compile
  {:shadow.build/stage :compile-prepare}
  [state]
  (start!)
  state)
